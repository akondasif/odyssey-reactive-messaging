package com.amadeus.middleware.odyssey.reactive.messaging.business.app;

import java.util.concurrent.TimeUnit;

import javax.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.reactivestreams.Publisher;

import com.amadeus.middleware.odyssey.reactive.messaging.core.Message;
import com.amadeus.middleware.odyssey.reactive.messaging.core.NodeName;
import com.amadeus.middleware.odyssey.reactive.messaging.kafka.connector.provider.KafkaTarget;

import io.reactivex.Flowable;

@ApplicationScoped
public class MyRxJavaProcessor {

  @SuppressWarnings("unchecked")
  @Incoming("rxin")
  @Outgoing("output_channel")
  @NodeName("stage6")
  public Publisher<Message<String>> stage6(Publisher<Message<String>> publisher) {
    return Flowable.fromPublisher(publisher)
        .flatMap(message -> {

          Message<String> child = Message.<String> builder()
              .fromParents(message)
              .payload(message.getPayload())
              .build();

          KafkaTarget target = child.getMetadata(KafkaTarget.META_KEY);
          target.topic(target.topic() + "-child");

          return Flowable.fromArray(message, child);
        })
        .delay(1, TimeUnit.SECONDS);
  }
}
