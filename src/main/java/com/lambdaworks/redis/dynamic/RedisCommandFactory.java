package com.lambdaworks.redis.dynamic;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.lambdaworks.redis.AbstractRedisReactiveCommands;
import com.lambdaworks.redis.LettuceFutures;
import com.lambdaworks.redis.RedisFuture;
import com.lambdaworks.redis.api.StatefulConnection;
import com.lambdaworks.redis.api.StatefulRedisConnection;
import com.lambdaworks.redis.api.async.BaseRedisAsyncCommands;
import com.lambdaworks.redis.api.reactive.BaseRedisReactiveCommands;
import com.lambdaworks.redis.codec.ByteArrayCodec;
import com.lambdaworks.redis.codec.RedisCodec;
import com.lambdaworks.redis.codec.StringCodec;
import com.lambdaworks.redis.dynamic.codec.AnnotationRedisCodecResolver;
import com.lambdaworks.redis.dynamic.domain.Timeout;
import com.lambdaworks.redis.dynamic.intercept.InvocationProxyFactory;
import com.lambdaworks.redis.dynamic.intercept.MethodInterceptor;
import com.lambdaworks.redis.dynamic.intercept.MethodInvocation;
import com.lambdaworks.redis.dynamic.output.CodecAwareOutputFactoryResolver;
import com.lambdaworks.redis.dynamic.output.CommandOutputFactoryResolver;
import com.lambdaworks.redis.dynamic.output.OutputRegistry;
import com.lambdaworks.redis.dynamic.output.OutputRegistryCommandOutputFactoryResolver;
import com.lambdaworks.redis.dynamic.parameter.ExecutionSpecificParameters;
import com.lambdaworks.redis.dynamic.segment.AnnotationCommandSegmentFactory;
import com.lambdaworks.redis.internal.LettuceAssert;
import com.lambdaworks.redis.internal.LettuceLists;
import com.lambdaworks.redis.output.CommandOutput;
import com.lambdaworks.redis.protocol.CommandArgs;
import com.lambdaworks.redis.protocol.LettuceCharsets;
import com.lambdaworks.redis.protocol.RedisCommand;

/**
 * Factory to create Redis Command interface instances.
 * <p>
 * This class is the entry point to implement command interfaces and obtain a reference to the implementation. Redis Command
 * interfaces provide a dynamic API that are declared in userland code. {@link RedisCommandFactory} and its supportive classes
 * analyze method declarations and derive from those factories to create and execute {@link RedisCommand}s.
 *
 * <h3>Example</h3> <code><pre>
public interface MyRedisCommands {

    String get(String key); // Synchronous Execution of GET

    &#64;Command("GET")
    byte[] getAsBytes(String key); // Synchronous Execution of GET returning data as byte array

    &#64;Command("SET") // synchronous execution applying a Timeout
    String setSync(String key, String value, Timeout timeout);

    Future<String> set(String key, String value); // asynchronous SET execution

    &#64;Command("SET")
    Mono<String> setReactive(String key, String value, SetArgs setArgs); // reactive SET execution using SetArgs

    &#64;CommandNaming(split = DOT) // support for Redis Module command notation -> NR.RUN
    double nrRun(String key, int... indexes);
}

 RedisCommandFactory factory = new RedisCommandFactory(connection);

 MyRedisCommands commands = factory.getCommands(MyRedisCommands.class);

 String value = commands.get("key");

 * </pre></code>
 *
 * @author Mark Paluch
 * @since 5.0
 * @see com.lambdaworks.redis.dynamic.annotation.Command
 * @see CommandMethod
 */
public class RedisCommandFactory {

    private final StatefulConnection<?, ?> connection;
    private final List<RedisCodec<?, ?>> redisCodecs = new ArrayList<>();

    private CommandOutputFactoryResolver commandOutputFactoryResolver = new OutputRegistryCommandOutputFactoryResolver(
            new OutputRegistry());

    /**
     * Create a new {@link CommandFactory} given {@link StatefulConnection}.
     * 
     * @param connection must not be {@literal null}.
     */
    public RedisCommandFactory(StatefulConnection<?, ?> connection) {
        this(connection, LettuceLists.newList(new ByteArrayCodec(), new StringCodec(LettuceCharsets.UTF8)));
    }

    /**
     * Create a new {@link CommandFactory} given {@link StatefulConnection} and a {@link List} of {@link RedisCodec}s to use
     *
     * @param connection must not be {@literal null}.
     * @param redisCodecs must not be {@literal null}.
     */
    public RedisCommandFactory(StatefulConnection<?, ?> connection, List<RedisCodec<?, ?>> redisCodecs) {

        LettuceAssert.notNull(connection, "Redis Connection must not be null");
        LettuceAssert.notNull(redisCodecs, "List of RedisCodecs must not be null");

        this.connection = connection;
        this.redisCodecs.addAll(redisCodecs);
    }

    /**
     * Set a {@link CommandOutputFactoryResolver}.
     * 
     * @param commandOutputFactoryResolver must not be {@literal null}.
     */
    public void setCommandOutputFactoryResolver(CommandOutputFactoryResolver commandOutputFactoryResolver) {

        LettuceAssert.notNull(commandOutputFactoryResolver, "CommandOutputFactoryResolver must not be null");

        this.commandOutputFactoryResolver = commandOutputFactoryResolver;
    }

    /**
     * Returns a Redis Command instance for the given interface.
     * 
     * @param commandInterface must not be {@literal null}.
     * @return the implemented Redis Commands interface.
     */
    public <T> T getCommands(Class<T> commandInterface) {

        LettuceAssert.notNull(commandInterface, "Redis Command Interface must not be null");

        RedisCommandsMetadata redisCommandsMetadata = new DefaultRedisCommandsMetadata(commandInterface);

        InvocationProxyFactory factory = new InvocationProxyFactory();
        factory.addInterface(commandInterface);

        if (connection instanceof StatefulRedisConnection<?, ?>) {

            StatefulRedisConnection<?, ?> redisConnection = (StatefulRedisConnection<?, ?>) connection;

            factory.addInterceptor(
                    new ReactiveCommandFactoryExecutorMethodInterceptor(redisCommandsMetadata, redisConnection.reactive()));

            factory.addInterceptor(new CommandFactoryExecutorMethodInterceptor(redisCommandsMetadata, redisConnection.async()));
        }

        return factory.createProxy(commandInterface.getClassLoader());
    }

    /**
     * {@link CommandFactory}-based {@link MethodInterceptor} to create and invoke Redis Commands using asynchronous and
     * synchronous execution models.
     *
     * @author Mark Paluch
     */
    class CommandFactoryExecutorMethodInterceptor implements MethodInterceptor {

        private final Map<Method, CommandFactory> commandFactories = new ConcurrentHashMap<>();
        private final BaseRedisAsyncCommands<?, ?> redisAsyncCommands;

        public CommandFactoryExecutorMethodInterceptor(RedisCommandsMetadata redisCommandsMetadata,
                BaseRedisAsyncCommands<?, ?> redisAsyncCommands) {

            RedisCommandFactoryResolver lookupStrategy = new DefaultRedisCommandFactoryResolver(redisCodecs);

            for (Method method : redisCommandsMetadata.getMethods()) {

                if (ReactiveWrappers.supports(method.getReturnType())) {
                    continue;
                }

                CommandFactory commandFactory = lookupStrategy.resolveRedisCommandFactory(method, redisCommandsMetadata);
                commandFactories.put(method, commandFactory);
            }

            this.redisAsyncCommands = redisAsyncCommands;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {

            Method method = invocation.getMethod();
            Object[] arguments = invocation.getArguments();

            if (hasFactoryFor(method)) {

                CommandMethod commandMethod = new CommandMethod(method);
                CommandFactory commandFactory = commandFactories.get(method);
                RedisCommand<?, ?, ?> command = commandFactory.createCommand(arguments);

                if (commandMethod.isFutureExecution()) {
                    return redisAsyncCommands.dispatch(command.getType(), (CommandOutput) command.getOutput(),
                            (CommandArgs) command.getArgs());
                }

                RedisFuture dispatch = redisAsyncCommands.dispatch(command.getType(), (CommandOutput) command.getOutput(),
                        (CommandArgs) command.getArgs());

                long timeout = connection.getTimeout();
                TimeUnit unit = connection.getTimeoutUnit();

                if (commandMethod.getParameters() instanceof ExecutionSpecificParameters) {
                    ExecutionSpecificParameters executionSpecificParameters = (ExecutionSpecificParameters) commandMethod
                            .getParameters();

                    if (executionSpecificParameters.hasTimeoutIndex()) {
                        Timeout timeoutArg = (Timeout) arguments[executionSpecificParameters.getTimeoutIndex()];
                        if (timeoutArg != null) {
                            timeout = timeoutArg.getTimeout();
                            unit = timeoutArg.getTimeUnit();
                        }
                    }
                }

                LettuceFutures.awaitAll(timeout, unit, dispatch);

                return dispatch.get();
            }

            return invocation.proceed();
        }

        private boolean hasFactoryFor(Method method) {
            return commandFactories.containsKey(method);
        }
    }

    /**
     * {@link CommandFactory}-based {@link MethodInterceptor} to create and invoke Redis Commands using reactive execution.
     *
     * @author Mark Paluch
     */
    class ReactiveCommandFactoryExecutorMethodInterceptor implements MethodInterceptor {

        private final Map<Method, ReactiveCommandSegmentCommandFactory> commandFactories = new ConcurrentHashMap<>();
        private final AbstractRedisReactiveCommands<?, ?> redisReactiveCommands;

        public ReactiveCommandFactoryExecutorMethodInterceptor(RedisCommandsMetadata redisCommandsMetadata,
                BaseRedisReactiveCommands<?, ?> redisReactiveCommands) {

            ReactiveRedisCommandFactoryResolver lookupStrategy = new ReactiveRedisCommandFactoryResolver(redisCodecs);

            for (Method method : redisCommandsMetadata.getMethods()) {

                if (ReactiveWrappers.supports(method.getReturnType())) {

                    ReactiveCommandSegmentCommandFactory commandFactory = lookupStrategy.resolveRedisCommandFactory(method,
                            redisCommandsMetadata);
                    commandFactories.put(method, commandFactory);
                }
            }

            this.redisReactiveCommands = (AbstractRedisReactiveCommands) redisReactiveCommands;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {

            Method method = invocation.getMethod();
            Object[] arguments = invocation.getArguments();

            if (hasFactoryFor(method)) {

                ReactiveCommandSegmentCommandFactory commandFactory = commandFactories.get(method);

                if (ReactiveWrappers.isSingle(method.getReturnType())) {
                    return redisReactiveCommands.createMono(() -> (RedisCommand) commandFactory.createCommand(arguments));
                }

                if (commandFactory.isStreamingExecution()) {

                    return redisReactiveCommands
                            .createDissolvingFlux(() -> (RedisCommand) commandFactory.createCommand(arguments));
                }

                return redisReactiveCommands.createFlux(() -> (RedisCommand) commandFactory.createCommand(arguments));
            }

            return invocation.proceed();
        }

        private boolean hasFactoryFor(Method method) {
            return commandFactories.containsKey(method);
        }
    }

    @SuppressWarnings("unchecked")
    class DefaultRedisCommandFactoryResolver implements RedisCommandFactoryResolver {

        final AnnotationCommandSegmentFactory commandSegmentFactory = new AnnotationCommandSegmentFactory();
        final AnnotationRedisCodecResolver codecResolver;

        DefaultRedisCommandFactoryResolver(List<RedisCodec<?, ?>> redisCodecs) {
            codecResolver = new AnnotationRedisCodecResolver(redisCodecs);
        }

        @Override
        public CommandFactory resolveRedisCommandFactory(Method method, RedisCommandsMetadata redisCommandsMetadata) {

            CommandMethod commandMethod = new CommandMethod(method);

            RedisCodec<?, ?> codec = codecResolver.resolve(commandMethod);

            if (codec == null) {
                throw new IllegalStateException(String.format("Cannot resolve codec for command method %s", method));
            }

            CodecAwareOutputFactoryResolver outputFactoryResolver = new CodecAwareOutputFactoryResolver(
                    commandOutputFactoryResolver, codec);
            if (commandMethod.isReactiveExecution()) {

                return new ReactiveCommandSegmentCommandFactory(commandSegmentFactory.createCommandSegments(commandMethod),
                        commandMethod, (RedisCodec) codec, outputFactoryResolver);
            }

            return new CommandSegmentCommandFactory<>(commandSegmentFactory.createCommandSegments(commandMethod), commandMethod,
                    (RedisCodec) codec, outputFactoryResolver);
        }
    }

    class ReactiveRedisCommandFactoryResolver implements RedisCommandFactoryResolver {

        final AnnotationCommandSegmentFactory commandSegmentFactory = new AnnotationCommandSegmentFactory();
        final AnnotationRedisCodecResolver codecResolver;

        ReactiveRedisCommandFactoryResolver(List<RedisCodec<?, ?>> redisCodecs) {
            codecResolver = new AnnotationRedisCodecResolver(redisCodecs);
        }

        @Override
        public ReactiveCommandSegmentCommandFactory resolveRedisCommandFactory(Method method,
                RedisCommandsMetadata redisCommandsMetadata) {

            CommandMethod commandMethod = new CommandMethod(method);

            RedisCodec<?, ?> codec = codecResolver.resolve(commandMethod);

            if (codec == null) {
                throw new IllegalStateException(String.format("Cannot resolve codec for command method %s", method));
            }

            CodecAwareOutputFactoryResolver outputFactoryResolver = new CodecAwareOutputFactoryResolver(
                    commandOutputFactoryResolver, codec);

            return new ReactiveCommandSegmentCommandFactory(commandSegmentFactory.createCommandSegments(commandMethod),
                    commandMethod, (RedisCodec) codec, outputFactoryResolver);
        }
    }

    /**
     * Strategy interface to resolve a {@link CommandFactory}.
     */
    interface RedisCommandFactoryResolver {

        CommandFactory resolveRedisCommandFactory(Method method, RedisCommandsMetadata redisCommandsMetadata);
    }
}