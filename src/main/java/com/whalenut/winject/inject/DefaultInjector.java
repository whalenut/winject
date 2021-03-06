package com.whalenut.winject.inject;


import com.whalenut.winject.inject.exceptions.WinjectException;
import com.whalenut.winject.inject.exceptions.WinjectInstantiationException;
import com.whalenut.winject.inject.exceptions.WinjectProviderCreationException;
import com.whalenut.winject.inject.exceptions.WinjectSetterException;
import com.whalenut.winject.mapping.BasicMappingInstance;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class DefaultInjector implements Injector {

    private final Map<String, Object> graph;
    private final Map<String, BasicMappingInstance> mappings;

    public DefaultInjector() {
        graph = new HashMap<>();
        mappings = new HashMap<>();
    }

    @Override
    public <T> BasicMappingInstance map(Class<T> from) {
        BasicMappingInstance basicMappingInstance = new BasicMappingInstance(from);
        mappings.put(from.getName(), basicMappingInstance);
        return basicMappingInstance;
    }

    @Override
    public <T> T create(Class<T> clazz) {
        if (mappings.containsKey(clazz.getName())) {
            clazz = mappings.get(clazz.getName()).get();
        }

        if (clazz.isAnnotationPresent(Singleton.class) && graph.containsKey(clazz.getName())) {
            return (T) graph.get(clazz.getName());
        }

        T instance;
        Optional<? extends Constructor<?>> constructor = getInjectableConstructor(clazz);
        if (!constructor.isPresent()) {
            instance = checkNoArgs(clazz);
        } else {
            instance = buildTree(constructor);
        }
        handleInjectableSetters(instance, clazz);

        return instance;
    }

    private <T> void handleInjectableSetters(final T instance, final Class<T> clazz) {
        Arrays.stream(instance.getClass().getDeclaredMethods())
                .filter(this::filterInjectables)
                .forEach(method -> {
                    try {
                        Object[] objects = Arrays.asList(method.getParameterTypes()).stream()
                                .map(this::create)
                                .toArray();
                        method.invoke(instance, objects);
                    } catch (IllegalAccessException | InvocationTargetException e) {
                        String message = String.format("Could not inject candidate for class %s in method %s",
                                clazz.getName(),
                                method.getName());
                        throw new WinjectSetterException(message, e);
                    }
                });
    }

    private boolean filterInjectables(Method method) {
        return Arrays.asList(method.getDeclaredAnnotations())
                .stream()
                .map(Annotation::annotationType)
                .collect(Collectors.toList()).contains(Inject.class);
    }

    private Provider<?> createProvider(Type type) {
        return () -> {
            try {
                return create(Class.forName(type.getTypeName()));
            } catch (ClassNotFoundException e) {
                throw new WinjectProviderCreationException("Could not create provider", e);
            }
        };
    }

    private <T> Optional<? extends Constructor<?>> getInjectableConstructor(final Class<T> clazz) {
        //If the it is an interface find a suitable implementation.
        if (clazz.isInterface()) {
//            loader.classes.stream().filter(c -> c.getInterfaces().length > 0).filter(c -> c.isInterface() == false).forEach(c -> System.out.println(c.getInterfaces()[0]));
//            System.out.println(clazz.getName());
        }

        return Stream.of(clazz.getDeclaredConstructors()).filter(ctor -> {
            Optional<Inject> injectableConstructor = Optional.ofNullable(ctor.getAnnotation(Inject.class));
            return injectableConstructor.isPresent();
        }).findFirst();
    }

    private <T> T buildTree(final Optional<? extends Constructor<?>> constructor) {
        Constructor<?> injectableConstructor = constructor.orElseThrow(
                () -> new WinjectInstantiationException("No constructor available."));
        List<Object> objects = Stream.of(injectableConstructor.getParameters())
                .map(p -> {
                    if (p.getType().equals(Provider.class)) {
                        return createProvider(((ParameterizedType) p.getParameterizedType()).getActualTypeArguments()[0]);
                    }
                    return create(p.getType());
                })
                .collect(Collectors.toList());

        try {
            T instance = (T) injectableConstructor.newInstance(objects.toArray());
            populateFields(instance);
            graph.put(instance.getClass().getName(), instance);
            return instance;
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            var message = String.format("Could not create instance of %s", constructor.getClass().getName());
            throw new WinjectInstantiationException(message, e);
        }
    }

    private <T> T checkNoArgs(final Class<T> clazz) {
        Optional<? extends Constructor<?>> noArgsConstructor = Arrays.asList(clazz.getConstructors())
                .stream()
                .filter(constructor -> constructor.getParameterCount() == 0)
                .findFirst();

        if (noArgsConstructor.isPresent()) {
            try {
                T instance = clazz.getDeclaredConstructor().newInstance();
                populateFields(instance);
                graph.put(instance.getClass().getName(), instance);
                return instance;
            } catch (RuntimeException | IllegalAccessException | InstantiationException | NoSuchMethodException | InvocationTargetException e) {
                var message = String.format("Could not create instance of %s", clazz.getName());
                throw new WinjectInstantiationException(message, e);
            }
        }
        var message = String.format("No injectable, or no zero-arguments constructor found for class %s", clazz.getName());
        throw new WinjectInstantiationException(message);
    }

    private <T> void populateFields(T target) {
        Arrays.stream(target.getClass().getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Inject.class))
                .forEach(field -> populateField(field, target));
    }

    private <T> void populateField(Field field, T target) {
        boolean accessible = field.canAccess(target);
        try {
            field.setAccessible(true);
            field.set(target, create(field.getType()));
            field.setAccessible(accessible);
        } catch (IllegalAccessException e) {
            var message = String.format("Cannot access field: %s in class: %s",
                    field.getName(),
                    target.getClass().getCanonicalName());
            throw new WinjectException(message, e);
        }
    }

}
