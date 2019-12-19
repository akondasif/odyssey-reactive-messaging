package com.amadeus.middleware.odyssey.reactive.messaging.core.cdiextension;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.enterprise.event.Observes;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.AfterDeploymentValidation;
import javax.enterprise.inject.spi.AnnotatedMethod;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.inject.spi.ProcessInjectionPoint;
import javax.enterprise.inject.spi.ProcessManagedBean;

import org.apache.commons.lang3.reflect.TypeUtils;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amadeus.middleware.odyssey.reactive.messaging.core.Async;
import com.amadeus.middleware.odyssey.reactive.messaging.core.Message;
import com.amadeus.middleware.odyssey.reactive.messaging.core.MessageContext;
import com.amadeus.middleware.odyssey.reactive.messaging.core.MessageContextBuilder;
import com.amadeus.middleware.odyssey.reactive.messaging.core.MessageScoped;
import com.amadeus.middleware.odyssey.reactive.messaging.core.impl.FunctionInvocationException;
import com.amadeus.middleware.odyssey.reactive.messaging.core.impl.MessageContextFactory;
import com.amadeus.middleware.odyssey.reactive.messaging.core.impl.PublisherInvoker;
import com.amadeus.middleware.odyssey.reactive.messaging.core.impl.ReactiveMessagingContext;
import com.amadeus.middleware.odyssey.reactive.messaging.core.topology.Topology;
import com.amadeus.middleware.odyssey.reactive.messaging.core.topology.TopologyBuilder;

public class StreamExtension implements Extension {
  private static final Logger logger = LoggerFactory.getLogger(StreamExtension.class);

  // Register it into ReactiveMessagingContext ?
  private MessageContextFactory messageContextFactory = new MessageContextFactoryImpl();

  private TopologyBuilder builder = new TopologyBuilder();
  private List<AnnotatedType<?>> messageContexts = new ArrayList<>();

  <T> void vetoAllMessageContextTypes(@Observes ProcessAnnotatedType<T> event) {

    Class<?> theClass = event.getAnnotatedType()
        .getJavaClass();

    logger.trace("vetoAllMessageContextTypes = {}", theClass);
    if (Async.class.isAssignableFrom(theClass)) {
      event.veto();
    }

    // Skip non-MessageContext
    if (!MessageContext.class.isAssignableFrom(theClass)) {
      return;
    }

    // Check whether this is annotated with MessageScoped
    if (!AnnotationUtils.hasAnnotation(theClass, MessageScoped.class)) {
      return;
    }

    logger.debug("veto {}", theClass.getName());
    event.veto();

    // Register the vetoed type
    messageContexts.add(event.getAnnotatedType());
  }

  // Here, the injection point will be transformed as follow:
  // Message<T> => Message
  // Async<WhatEver<T>> => Async<Whatever>
  <T, X> void reshapeParameterizedInjectionPoint(@Observes ProcessInjectionPoint<T, X> event) {
    Member member = event.getInjectionPoint()
        .getMember();
    Class<?> dc = member.getDeclaringClass();

    try {
      Field field = dc.getDeclaredField(member.getName());
      if (field != null) {
        field.setAccessible(true);
        if (field.getGenericType() instanceof ParameterizedType) {
          ParameterizedType parameterizedType = (ParameterizedType) field.getGenericType();
          if (Message.class.isAssignableFrom(field.getType())) {
            event.configureInjectionPoint()
                .type(Message.class);
          } else if (Async.class.isAssignableFrom(field.getType())
              && parameterizedType.getActualTypeArguments().length == 1) {
            Type asyncTypeParameters = parameterizedType.getActualTypeArguments()[0];
            if (asyncTypeParameters instanceof ParameterizedType) {
              ParameterizedType pt = (ParameterizedType) asyncTypeParameters;
              event.configureInjectionPoint()
                  .type(TypeUtils.parameterize(Async.class, pt.getRawType()));
            }
          }
        }
      }
    } catch (NoSuchFieldException e) {
      // TODO: filter before this can happen
      logger.warn("argh! {}.{}", dc.getName(), member.getName());
    }
  }

  @SuppressWarnings("unchecked")
  <T> void processManagedBean(@Observes ProcessManagedBean<T> event) {
    AnnotatedType<?> annotatedType = event.getAnnotatedBeanClass();
    annotatedType.getMethods()
        .stream()
        .filter(m -> m.isAnnotationPresent(Incoming.class) || m.isAnnotationPresent(Outgoing.class))
        .forEach(method -> processFlowingMethod(annotatedType, method));

    annotatedType.getMethods()
        .stream()
        .filter(m -> m.isAnnotationPresent(MessageContextBuilder.class))
        .forEach(annotatedMethod -> {
          Class<? extends MessageContext> returnType = (Class<? extends MessageContext>) annotatedMethod.getJavaMember()
              .getReturnType();
          messageContextFactory.add(returnType, annotatedMethod.getJavaMember());
          messageContextFactory.add(returnType, event.getAnnotatedBeanClass()
              .getJavaClass());
        });
  }

  public void processFlowingMethod(AnnotatedType<?> annotatedType, AnnotatedMethod<?> method) {
    if (method.isAnnotationPresent(Incoming.class)) {
      processFlowingProcessor(annotatedType, method);
    } else if (method.isAnnotationPresent(Outgoing.class)) {
      processFlowingPublisher(annotatedType, method);
    }
  }

  private void processFlowingProcessor(AnnotatedType<?> annotatedType, AnnotatedMethod<?> method) {
    CDIFunctionInvoker functionInvoker = new CDIFunctionInvoker(annotatedType.getJavaClass(), method.getJavaMember());
    Stream<?> is = method.getAnnotations(Incoming.class)
        .stream()
        .map(annotation -> ((Incoming) annotation).value());
    String[] inputChannels = is.collect(Collectors.toList())
        .toArray(new String[] {});
    Stream<?> os = method.getAnnotations(Outgoing.class)
        .stream()
        .map(annotation -> ((Outgoing) annotation).value());
    String[] outputChannels = os.collect(Collectors.toList())
        .toArray(new String[] {});
    builder.addProcessor(annotatedType.getJavaClass()
        .getName() + "."
        + method.getJavaMember()
            .getName(),
        functionInvoker, inputChannels, outputChannels);
  }

  private void processFlowingPublisher(AnnotatedType<?> annotatedType, AnnotatedMethod<?> method) {
    PublisherInvoker<?> publisherInvoker = new PublisherInvoker<>(annotatedType.getJavaClass(), method.getJavaMember());
    Stream<String> os = method.getAnnotations(Outgoing.class)
        .stream()
        .map(annotation -> ((Outgoing) annotation).value());
    String[] outputChannels = os.collect(Collectors.toList())
        .toArray(new String[] {});

    builder.addPublisher(annotatedType.getJavaClass()
        .getName() + "."
        + method.getJavaMember()
            .getName(),
        publisherInvoker, outputChannels);
  }

  @SuppressWarnings("rawtypes")
  void afterBeanDiscovery(@Observes AfterBeanDiscovery abd) {

    abd.<MessageContext> addBean()
        .types(Message.class)
        .createWith(new ProxyProducer<>(Message.class));

    ParameterizedType asyncType = TypeUtils.parameterize(Async.class, Message.class);

    logger.debug("registering producer for MessageScoped.class: {}", asyncType);
    abd.<Async> addBean()
        .types(asyncType)
        .createWith(cc -> new CDIAsync<>(Message.class));

    logger.trace("Registering MessageScopedContext");
    messageContexts.stream()
        // Skip producer exposition for non-directly annotated classes
        .filter(annotatedType -> annotatedType.getJavaClass()
            .isAnnotationPresent(MessageScoped.class))
        .forEach(annotatedType -> registerMessageScopedProducer(abd, annotatedType));
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  void registerMessageScopedProducer(AfterBeanDiscovery abd, AnnotatedType<?> at) {
    Class<?> clazz = at.getJavaClass();

    logger.debug("registering producer for MessageScoped.class: {}", clazz.getName());
    abd.<MessageContext> addBean()
        .types(clazz)
        .createWith(new ProxyProducer<>(clazz));

    ParameterizedType asyncType = TypeUtils.parameterize(Async.class, at.getJavaClass());

    logger.debug("registering producer for MessageScoped.class: {}", asyncType);
    abd.<Async> addBean()
        .types(asyncType)
        .createWith(cc -> new CDIAsync(at.getJavaClass()));
  }

  void afterDeploymentValidation(@Observes AfterDeploymentValidation abd, BeanManager beanManager)
      throws FunctionInvocationException {
    logger.debug("AfterDeploymentValidation");

    ReactiveMessagingContext.setMessageContextFactory(messageContextFactory);

    Instance<Object> instance = beanManager.createInstance();
    // Build the topology
    builder.build(instance.select(Topology.class)
        .get());
    builder = null;

    logger.info("StreamExtension initialized");
  }

}