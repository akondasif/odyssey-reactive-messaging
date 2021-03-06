package com.amadeus.middleware.odyssey.reactive.messaging.core.cdiextension;

import javax.inject.Inject;

import com.amadeus.middleware.odyssey.reactive.messaging.core.impl.cdi.MessageScopedContext;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.amadeus.middleware.odyssey.reactive.messaging.core.Message;
import com.amadeus.middleware.odyssey.reactive.messaging.core.impl.MessageImpl;

@RunWith(Arquillian.class)
public class MyProcessorTest {

  @Deployment
  public static JavaArchive createDeployment() {
    return ShrinkWrap.create(JavaArchive.class)
        .addClasses(MyProcessor.class)
        .addPackages(true, "com/amadeus/middleware/odyssey/reactive/messaging/core")
        .addAsResource("logback.xml")
        .addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml");
  }

  @Inject
  MyProcessor myProcessor;

  @Test
  public void basicTest() {
    MessageImpl<String> msg = (MessageImpl<String>) Message.<String> builder()
        .payload("hello")
        .build();

    MessageScopedContext msc = MessageScopedContext.getInstance();
    msc.start(msg.getScopeContextId());
    try {
      Assert.assertNotNull(myProcessor);
      Assert.assertEquals("hello", myProcessor.getMessage()
          .getPayload());
      Assert.assertEquals("hello", myProcessor.getMessageString()
          .getPayload());
      Assert.assertEquals("hello", myProcessor.getAsyncMessage()
          .get()
          .getPayload());
    } finally {
      msc.suspend();
    }
  }

}
