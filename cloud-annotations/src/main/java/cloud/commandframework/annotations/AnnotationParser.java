//
// MIT License
//
// Copyright (c) 2022 Alexander Söderberg & Contributors
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//
package cloud.commandframework.annotations;

import cloud.commandframework.Command;
import cloud.commandframework.CommandComponent;
import cloud.commandframework.CommandManager;
import cloud.commandframework.annotations.injection.ParameterInjectorRegistry;
import cloud.commandframework.annotations.injection.RawArgs;
import cloud.commandframework.annotations.parsers.MethodArgumentParser;
import cloud.commandframework.annotations.parsers.Parser;
import cloud.commandframework.annotations.processing.CommandContainerProcessor;
import cloud.commandframework.annotations.suggestions.MethodSuggestionProvider;
import cloud.commandframework.annotations.suggestions.Suggestions;
import cloud.commandframework.arguments.flags.CommandFlag;
import cloud.commandframework.arguments.parser.ArgumentParser;
import cloud.commandframework.arguments.parser.ParserParameter;
import cloud.commandframework.arguments.parser.ParserParameters;
import cloud.commandframework.arguments.parser.StandardParameters;
import cloud.commandframework.arguments.preprocessor.RegexPreprocessor;
import cloud.commandframework.arguments.suggestion.SuggestionProvider;
import cloud.commandframework.captions.Caption;
import cloud.commandframework.context.CommandContext;
import cloud.commandframework.context.CommandInput;
import cloud.commandframework.execution.CommandExecutionHandler;
import cloud.commandframework.extra.confirmation.CommandConfirmationManager;
import cloud.commandframework.meta.CommandMeta;
import cloud.commandframework.meta.SimpleCommandMeta;
import cloud.commandframework.types.tuples.Pair;
import io.leangen.geantyref.GenericTypeReflector;
import io.leangen.geantyref.TypeToken;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Parser that parses class instances {@link Command commands}
 *
 * @param <C> Command sender type
 */
@SuppressWarnings("unused")
public final class AnnotationParser<C> {

    /**
     * The value of {@link Argument} that should be used to infer argument names from parameter names.
     */
    public static final String INFERRED_ARGUMENT_NAME = "__INFERRED_ARGUMENT_NAME__";

    private final CommandManager<C> manager;
    private final Map<Class<? extends Annotation>, AnnotationMapper<?>> annotationMappers;
    private final Map<Class<? extends Annotation>, PreprocessorMapper<?, C>> preprocessorMappers;
    private final Map<Class<? extends Annotation>, BuilderModifier<?, C>> builderModifiers;
    private final Map<Predicate<Method>, CommandMethodExecutionHandlerFactory<C>> commandMethodFactories;
    private final TypeToken<C> commandSenderType;
    private final MetaFactory metaFactory;

    private StringProcessor stringProcessor;
    private SyntaxParser syntaxParser;
    private ArgumentExtractor argumentExtractor;
    private ArgumentAssembler<C> argumentAssembler;
    private FlagExtractor flagExtractor;
    private FlagAssembler flagAssembler;
    private CommandExtractor commandExtractor;

    /**
     * Construct a new annotation parser
     *
     * @param manager            Command manager instance
     * @param commandSenderClass Command sender class
     * @param metaMapper         Function that is used to create {@link CommandMeta} instances from annotations on the
     *                           command methods. These annotations will be mapped to
     *                           {@link ParserParameter}. Mappers for the
     *                           parser parameters can be registered using {@link #registerAnnotationMapper(Class, AnnotationMapper)}
     */
    public AnnotationParser(
            final @NonNull CommandManager<C> manager,
            final @NonNull Class<C> commandSenderClass,
            final @NonNull Function<@NonNull ParserParameters, @NonNull CommandMeta> metaMapper
    ) {
        this(manager, TypeToken.get(commandSenderClass), metaMapper);
    }

    /**
     * Construct a new annotation parser
     *
     * @param manager           Command manager instance
     * @param commandSenderType Command sender type
     * @param metaMapper        Function that is used to create {@link CommandMeta} instances from annotations on the
     *                          command methods. These annotations will be mapped to
     *                          {@link ParserParameter}. Mappers for the
     *                          parser parameters can be registered using {@link #registerAnnotationMapper(Class, AnnotationMapper)}
     * @since 1.7.0
     */
    @API(status = API.Status.STABLE, since = "1.7.0")
    public AnnotationParser(
            final @NonNull CommandManager<C> manager,
            final @NonNull TypeToken<C> commandSenderType,
            final @NonNull Function<@NonNull ParserParameters, @NonNull CommandMeta> metaMapper
    ) {
        this.commandSenderType = commandSenderType;
        this.manager = manager;
        this.metaFactory = new MetaFactory(this, metaMapper);
        this.annotationMappers = new HashMap<>();
        this.preprocessorMappers = new HashMap<>();
        this.builderModifiers = new HashMap<>();
        this.commandMethodFactories = new HashMap<>();
        this.flagExtractor = new FlagExtractorImpl(this);
        this.flagAssembler = new FlagAssemblerImpl(manager);
        this.syntaxParser = new SyntaxParserImpl();
        this.argumentExtractor = new ArgumentExtractorImpl();
        this.argumentAssembler = new ArgumentAssemblerImpl<>(this);
        this.commandExtractor = new CommandExtractorImpl(this);
        this.registerAnnotationMapper(CommandDescription.class, d ->
                ParserParameters.single(StandardParameters.DESCRIPTION, this.processString(d.value())));
        this.registerPreprocessorMapper(Regex.class, annotation -> RegexPreprocessor.of(
                this.processString(annotation.value()),
                Caption.of(this.processString(annotation.failureCaption()))
        ));
        this.getParameterInjectorRegistry().registerInjector(
                String[].class,
                (context, annotations) -> annotations.annotation(RawArgs.class) == null
                        ? null
                        : context.rawInput().tokenize().toArray(new String[0])
        );
        this.stringProcessor = StringProcessor.noOp();
    }

    @SuppressWarnings("unchecked")
    static <A extends Annotation> @Nullable A getAnnotationRecursively(
            final @NonNull AnnotationAccessor annotations,
            final @NonNull Class<A> clazz,
            final @NonNull Set<Class<? extends Annotation>> checkedAnnotations
    ) {
        A innerCandidate = null;
        for (final Annotation annotation : annotations.annotations()) {
            if (!checkedAnnotations.add(annotation.annotationType())) {
                continue;
            }
            if (annotation.annotationType().equals(clazz)) {
                return (A) annotation;
            }
            if (annotation.annotationType().getPackage().getName().startsWith("java.lang")) {
                continue;
            }
            final A inner = getAnnotationRecursively(
                    AnnotationAccessor.of(annotation.annotationType()),
                    clazz,
                    checkedAnnotations
            );
            if (inner != null) {
                innerCandidate = inner;
            }
        }
        return innerCandidate;
    }

    static <A extends Annotation> @Nullable A getMethodOrClassAnnotation(
            final @NonNull Method method,
            final @NonNull Class<A> clazz
    ) {
        A annotation = getAnnotationRecursively(
                AnnotationAccessor.of(method),
                clazz,
                new HashSet<>()
        );
        if (annotation == null) {
            annotation = getAnnotationRecursively(
                    AnnotationAccessor.of(method.getDeclaringClass()),
                    clazz,
                    new HashSet<>()
            );
        }
        return annotation;
    }

    static <A extends Annotation> boolean methodOrClassHasAnnotation(
            final @NonNull Method method,
            final @NonNull Class<A> clazz
    ) {
        return getMethodOrClassAnnotation(method, clazz) != null;
    }

    /**
     * Returns the command manager that was used to create this parser
     *
     * @return Command manager
     * @since 1.6.0
     */
    public @NonNull CommandManager<C> manager() {
        return this.manager;
    }

    /**
     * Registers a new command execution method factory. This allows for the registration of
     * custom command method execution strategies.
     *
     * @param predicate the predicate that decides whether to apply the custom execution handler to the given method
     * @param factory   the function that produces the command execution handler
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public void registerCommandExecutionMethodFactory(
            final @NonNull Predicate<@NonNull Method> predicate,
            final @NonNull CommandMethodExecutionHandlerFactory<C> factory
    ) {
        this.commandMethodFactories.put(predicate, factory);
    }

    /**
     * Register a builder modifier for a specific annotation. The builder modifiers are
     * allowed to act on a {@link Command.Builder} after all arguments have been added
     * to the builder. This allows for modifications of the builder instance before
     * the command is registered to the command manager.
     *
     * @param annotation      Annotation (class) that the builder modifier reacts to
     * @param builderModifier Modifier that acts on the given annotation and the incoming builder. Command builders
     *                        are immutable, so the modifier should return the instance of the command builder that is
     *                        returned as a result of any operation on the builder
     * @param <A>             Annotation type
     */
    public <A extends Annotation> void registerBuilderModifier(
            final @NonNull Class<A> annotation,
            final @NonNull BuilderModifier<A, C> builderModifier
    ) {
        this.builderModifiers.put(annotation, builderModifier);
    }

    /**
     * Register an annotation mapper
     *
     * @param annotation Annotation class
     * @param mapper     Mapping function
     * @param <A>        Annotation type
     */
    public <A extends Annotation> void registerAnnotationMapper(
            final @NonNull Class<A> annotation,
            final @NonNull AnnotationMapper<A> mapper
    ) {
        this.annotationMappers.put(annotation, mapper);
    }

    /**
     * Registers a preprocessor mapper
     *
     * @param annotation         annotation class
     * @param preprocessorMapper preprocessor mapper
     * @param <A>                annotation type
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public <A extends Annotation> void registerPreprocessorMapper(
            final @NonNull Class<A> annotation,
            final @NonNull PreprocessorMapper<A, C> preprocessorMapper
    ) {
        this.preprocessorMappers.put(annotation, preprocessorMapper);
    }

    /**
     * Returns an unmodifiable view of the preprocessor mappers.
     *
     * @return the preprocessor mappers
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public @NonNull Map<@NonNull Class<? extends Annotation>, @NonNull PreprocessorMapper<?, C>> preprocessorMappers() {
        return Collections.unmodifiableMap(this.preprocessorMappers);
    }

    /**
     * Get the parameter injector registry instance that is used to inject non-{@link Argument argument} parameters
     * into {@link CommandMethod} annotated {@link Method methods}
     *
     * @return Parameter injector registry
     * @since 1.2.0
     */
    public @NonNull ParameterInjectorRegistry<C> getParameterInjectorRegistry() {
        return this.manager.parameterInjectorRegistry();
    }

    /**
     * Returns the string processor used by this parser.
     *
     * @return the string processor
     * @since 1.7.0
     */
    public @NonNull StringProcessor stringProcessor() {
        return this.stringProcessor;
    }

    /**
     * Replaces the string processor of this parser.
     *
     * @param stringProcessor the new string processor
     * @since 1.7.0
     */
    public void stringProcessor(final @NonNull StringProcessor stringProcessor) {
        this.stringProcessor = stringProcessor;
    }

    /**
     * Processes the {@code input} string and returns the processed result.
     *
     * @param input the input string
     * @return the processed string
     * @since 1.7.0
     */
    public @NonNull String processString(final @NonNull String input) {
        return this.stringProcessor().processString(input);
    }

    /**
     * Processes the input {@code strings} and returns the processed result.
     *
     * @param strings the input strings
     * @return the processed strings
     * @since 1.7.0
     */
    public @NonNull String[] processStrings(final @NonNull String[] strings) {
        return Arrays.stream(strings).map(this::processString).toArray(String[]::new);
    }

    /**
     * Processes the input {@code strings} and returns the processed result.
     *
     * @param strings the input strings
     * @return the processed strings
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public @NonNull List<@NonNull String> processStrings(final @NonNull Collection<@NonNull String> strings) {
        return strings.stream().map(this::processString).collect(Collectors.toList());
    }

    /**
     * Returns the syntax parser.
     *
     * @return the syntax parser
     * @since 2.0.0
     */
    public @NonNull SyntaxParser syntaxParser() {
        return this.syntaxParser;
    }

    /**
     * Sets the syntax parser.
     *
     * @param syntaxParser new syntax parser
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public void syntaxParser(final @NonNull SyntaxParser syntaxParser) {
        this.syntaxParser = syntaxParser;
    }

    /**
     * Returns the argument extractor.
     *
     * @return the argument extractor
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public @NonNull ArgumentExtractor argumentExtractor() {
        return this.argumentExtractor;
    }

    /**
     * Sets the argument extractor.
     *
     * @param argumentExtractor new argument extractor
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public void argumentExtractor(final @NonNull ArgumentExtractor argumentExtractor) {
        this.argumentExtractor = argumentExtractor;
    }

    /**
     * Returns the argument assembler.
     *
     * @return the argument assembler
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public @NonNull ArgumentAssembler<C> argumentAssembler() {
        return this.argumentAssembler;
    }

    /**
     * Sets the argument assembler
     *
     * @param argumentAssembler new argument assembler
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public void argumentAssembler(final @NonNull ArgumentAssembler<C> argumentAssembler) {
        this.argumentAssembler = argumentAssembler;
    }

    /**
     * Returns the flag extractor.
     *
     * @return the flag extractor
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public @NonNull FlagExtractor flagExtractor() {
        return this.flagExtractor;
    }

    /**
     * Sets the flag extractor.
     *
     * @param flagExtractor new flag extractor
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public void flagExtractor(final @NonNull FlagExtractor flagExtractor) {
        this.flagExtractor = flagExtractor;
    }

    /**
     * Returns the flag assembler.
     *
     * @return the flag assembler
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public @NonNull FlagAssembler flagAssembler() {
        return this.flagAssembler;
    }

    /**
     * Sets the flag assembler
     *
     * @param flagAssembler new flag assembler
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public void flagAssembler(final @NonNull FlagAssembler flagAssembler) {
        this.flagAssembler = flagAssembler;
    }

    /**
     * Returns the command extractor.
     *
     * @return the command extractor
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public @NonNull CommandExtractor commandExtractor() {
        return this.commandExtractor;
    }

    /**
     * Sets the command extractor.
     *
     * @param commandExtractor new command extractor
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public void commandExtractor(final @NonNull CommandExtractor commandExtractor) {
        this.commandExtractor = commandExtractor;
    }

    /**
     * Parses all known {@link cloud.commandframework.annotations.processing.CommandContainer command containers}.
     *
     * @return Collection of parsed commands
     * @throws Exception re-throws all encountered exceptions.
     * @see cloud.commandframework.annotations.processing.CommandContainer CommandContainer for more information.
     * @since 1.7.0
     */
    public @NonNull Collection<@NonNull Command<C>> parseContainers() throws Exception {
        final List<Command<C>> commands = new LinkedList<>();

        final List<String> classes;
        try (InputStream stream = this.getClass().getClassLoader().getResourceAsStream(CommandContainerProcessor.PATH)) {
            if (stream == null) {
                return Collections.emptyList();
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                classes = reader.lines().distinct().collect(Collectors.toList());
            }
        }

        for (final String className : classes) {
            final Class<?> commandContainer = Class.forName(className);

            // We now have the class, and we now just need to decide what constructor to invoke.
            // We first try to find a constructor which takes in the parser.
            @MonotonicNonNull Object instance;
            try {
                instance = commandContainer.getConstructor(AnnotationParser.class).newInstance(this);
            } catch (final NoSuchMethodException ignored) {
                try {
                    // Then we try to find a no-arg constructor.
                    instance = commandContainer.getConstructor().newInstance();
                } catch (final NoSuchMethodException e) {
                    // If neither are found, we panic!
                    throw new IllegalStateException(
                            String.format(
                                    "Command container %s has no valid constructors",
                                    commandContainer
                            ),
                            e
                    );
                }
            }
            commands.addAll(this.parse(instance));
        }

        return Collections.unmodifiableList(commands);
    }

    /**
     * Scan a class instance of {@link CommandMethod} annotations and attempt to
     * compile them into {@link Command} instances.
     *
     * @param instance Instance to scan
     * @param <T>      Type of the instance
     * @return Collection of parsed commands
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> @NonNull Collection<@NonNull Command<C>> parse(final @NonNull T instance) {
        this.parseSuggestions(instance);
        this.parseParsers(instance);

        final Collection<CommandDescriptor> commandDescriptors = this.commandExtractor.extractCommands(instance);
        final Collection<Command<C>> commands = this.construct(instance, commandDescriptors);
        for (final Command<C> command : commands) {
            ((CommandManager) this.manager).command(command);
        }
        return commands;
    }

    @SuppressWarnings("deprecation")
    private <T> void parseSuggestions(final @NonNull T instance) {
        for (final Method method : instance.getClass().getMethods()) {
            final Suggestions suggestions = method.getAnnotation(Suggestions.class);
            if (suggestions == null) {
                continue;
            }
            if (!method.isAccessible()) {
                method.setAccessible(true);
            }
            if (method.getParameterCount() != 2
                    || !(Iterable.class.isAssignableFrom(method.getReturnType()) || method.getReturnType().equals(Stream.class))
                    || !method.getParameters()[0].getType().equals(CommandContext.class)
                    || !method.getParameters()[1].getType().equals(String.class)
            ) {
                throw new IllegalArgumentException(String.format(
                        "@Suggestions annotated method '%s' in class '%s' does not have the correct signature",
                        method.getName(),
                        instance.getClass().getCanonicalName()
                ));
            }
            try {
                this.manager.parserRegistry().registerSuggestionProvider(
                        this.processString(suggestions.value()),
                        new MethodSuggestionProvider<>(instance, method)
                );
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private <T> void parseParsers(final @NonNull T instance) {
        for (final Method method : instance.getClass().getMethods()) {
            final Parser parser = method.getAnnotation(Parser.class);
            if (parser == null) {
                continue;
            }
            if (!method.isAccessible()) {
                method.setAccessible(true);
            }
            if (method.getParameterCount() != 2
                    || method.getReturnType().equals(Void.class)
                    || !method.getParameters()[0].getType().equals(CommandContext.class)
                    || !method.getParameters()[1].getType().equals(CommandInput.class)
            ) {
                throw new IllegalArgumentException(String.format(
                        "@Parser annotated method '%s' in class '%s' does not have the correct signature",
                        method.getName(),
                        instance.getClass().getCanonicalName()
                ));
            }
            try {
                final String suggestions = this.processString(parser.suggestions());
                final SuggestionProvider<C> suggestionProvider;
                if (suggestions.isEmpty()) {
                    suggestionProvider = (context, input) -> Collections.emptyList();
                } else {
                    suggestionProvider = this.manager.parserRegistry().getSuggestionProvider(suggestions)
                            .orElseThrow(() -> new NullPointerException(
                                    String.format(
                                            "Cannot find the suggestion provider with name '%s'",
                                            suggestions
                                    )
                            ));
                }
                final MethodArgumentParser<C, ?> methodArgumentParser = new MethodArgumentParser<>(
                        suggestionProvider,
                        instance,
                        method
                );
                final Function<ParserParameters, ArgumentParser<C, ?>> parserFunction =
                        parameters -> methodArgumentParser;
                final String name = this.processString(parser.name());
                if (name.isEmpty()) {
                    this.manager.parserRegistry().registerParserSupplier(
                            TypeToken.get(method.getGenericReturnType()),
                            parserFunction
                    );
                } else {
                    this.manager.parserRegistry().registerNamedParserSupplier(
                            name,
                            parserFunction
                    );
                }
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private @NonNull Collection<@NonNull Command<C>> construct(
            final @NonNull Object instance,
            final @NonNull Collection<@NonNull CommandDescriptor> commandDescriptors
    ) {
        return commandDescriptors.stream()
                .flatMap(descriptor -> this.constructCommands(instance, descriptor).stream())
                .collect(Collectors.toList());
    }

    @SuppressWarnings({"unchecked"})
    private @NonNull Collection<@NonNull Command<C>> constructCommands(
            final @NonNull Object instance,
            final @NonNull CommandDescriptor commandDescriptor
    ) {
        final AnnotationAccessor classAnnotations = AnnotationAccessor.of(instance.getClass());
        final List<Command<C>> commands = new ArrayList<>();

        final Method method = commandDescriptor.method();
        final CommandManager<C> manager = this.manager;
        final SimpleCommandMeta.Builder metaBuilder = SimpleCommandMeta.builder()
                .with(this.metaFactory.apply(method));
        if (methodOrClassHasAnnotation(method, Confirmation.class)) {
            metaBuilder.with(CommandConfirmationManager.META_CONFIRMATION_REQUIRED, true);
        }

        Command.Builder<C> builder = manager.commandBuilder(
                commandDescriptor.commandToken(),
                commandDescriptor.syntax().get(0).getMinor(),
                metaBuilder.build()
        );
        final Collection<ArgumentDescriptor> arguments = this.argumentExtractor.extractArguments(commandDescriptor.syntax(), method);
        final Collection<FlagDescriptor> flagDescriptors = this.flagExtractor.extractFlags(method);
        final Collection<CommandFlag<?>> flags = flagDescriptors.stream()
                .map(this.flagAssembler()::assembleFlag)
                .collect(Collectors.toList());
        final Map<String, CommandComponent<C>> commandComponents = this.constructComponents(arguments, commandDescriptor);

        boolean commandNameFound = false;
        /* Build the command tree */
        for (final SyntaxFragment token : commandDescriptor.syntax()) {
            if (!commandNameFound) {
                commandNameFound = true;
                continue;
            }
            if (token.getArgumentMode() == ArgumentMode.LITERAL) {
                builder = builder.literal(token.getMajor(), token.getMinor().toArray(new String[0]));
            } else {
                final CommandComponent<C> component = commandComponents.get(token.getMajor());
                if (component == null) {
                    throw new IllegalArgumentException(String.format(
                            "Found no mapping for argument '%s' in method '%s'",
                            token.getMajor(), method.getName()
                    ));
                }

                builder = builder.argument(component);
            }
        }
        /* Try to find the command sender type */
        Class<? extends C> senderType = null;
        for (final Parameter parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(Argument.class)) {
                continue;
            }
            if (GenericTypeReflector.isSuperType(this.commandSenderType.getType(), parameter.getType())) {
                senderType = (Class<? extends C>) parameter.getType();
                break;
            }
        }

        final CommandPermission commandPermission = getMethodOrClassAnnotation(method, CommandPermission.class);
        if (commandPermission != null) {
            builder = builder.permission(this.processString(commandPermission.value()));
        }

        if (commandDescriptor.requiredSender() != Object.class) {
            builder = builder.senderType((Class<? extends C>) commandDescriptor.requiredSender());
        } else if (senderType != null) {
            builder = builder.senderType(senderType);
        }
        try {
            final MethodCommandExecutionHandler.CommandMethodContext<C> context =
                    new MethodCommandExecutionHandler.CommandMethodContext<>(
                            instance,
                            commandComponents,
                            arguments,
                            flagDescriptors,
                            method,
                            this /* annotationParser */
                    );

            /* Create the command execution handler */
            CommandExecutionHandler<C> commandExecutionHandler = new MethodCommandExecutionHandler<>(context);
            for (final Map.Entry<Predicate<Method>, CommandMethodExecutionHandlerFactory<C>> entry
                    : this.commandMethodFactories.entrySet()) {
                if (entry.getKey().test(method)) {
                    commandExecutionHandler = entry.getValue().createExecutionHandler(context);

                    /* Once we have our custom handler, we stop */
                    break;
                }
            }

            builder = builder.handler(commandExecutionHandler);
        } catch (final Exception e) {
            throw new RuntimeException("Failed to construct command execution handler", e);
        }
        /* Check if the command should be hidden */
        if (methodOrClassHasAnnotation(method, Hidden.class)) {
            builder = builder.hidden();
        }
        /* Apply flags */
        for (final CommandFlag<?> flag : flags) {
            builder = builder.flag(flag);
        }

        /* Apply builder modifiers */
        for (final Annotation annotation
                : AnnotationAccessor.of(classAnnotations, AnnotationAccessor.of(method)).annotations()) {
            @SuppressWarnings("rawtypes")
            final BuilderModifier builderModifier = this.builderModifiers.get(
                    annotation.annotationType()
            );
            if (builderModifier == null) {
                continue;
            }
            builder = (Command.Builder<C>) builderModifier.modifyBuilder(annotation, builder);
        }

        /* Construct and register the command */
        final Command<C> builtCommand = builder.build();
        commands.add(builtCommand);

        if (method.isAnnotationPresent(ProxiedBy.class)) {
            manager.command(this.constructProxy(method.getAnnotation(ProxiedBy.class), builtCommand));
        }

        return commands;
    }

    private @NonNull Map<@NonNull String, @NonNull CommandComponent<C>> constructComponents(
            final @NonNull Collection<@NonNull ArgumentDescriptor> arguments,
            final @NonNull CommandDescriptor commandDescriptor
    ) {
        return arguments.stream()
                .map(argumentDescriptor -> this.argumentAssembler.assembleArgument(
                        this.findSyntaxFragment(commandDescriptor.syntax(), this.processString(argumentDescriptor.name())),
                        argumentDescriptor
                )).map(component -> Pair.of(component.argument().getName(), component))
                .collect(Collectors.toMap(Pair::getFirst, Pair::getSecond));
    }

    private @NonNull Command<C> constructProxy(
            final @NonNull ProxiedBy proxyAnnotation,
            final @NonNull Command<C> command
    ) {
        final String proxy = this.processString(proxyAnnotation.value());
        if (proxy.contains(" ")) {
            throw new IllegalArgumentException("@ProxiedBy proxies may only contain single literals");
        }
        Command.Builder<C> proxyBuilder = this.manager.commandBuilder(proxy, command.getCommandMeta())
                .proxies(command);
        if (proxyAnnotation.hidden()) {
            proxyBuilder = proxyBuilder.hidden();
        }
        return proxyBuilder.build();
    }

    private @NonNull SyntaxFragment findSyntaxFragment(
            final @NonNull List<@NonNull SyntaxFragment> fragments,
            final @NonNull String argumentName
    ) {
        return fragments.stream().filter(fragment -> fragment.getArgumentMode() != ArgumentMode.LITERAL)
                .filter(fragment -> fragment.getMajor().equals(argumentName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Argument is not declared in syntax: " + argumentName));
    }

    @NonNull Map<@NonNull Class<@NonNull ? extends Annotation>, AnnotationMapper<?>> annotationMappers() {
        return this.annotationMappers;
    }
}
