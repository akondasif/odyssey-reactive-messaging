package com.amadeus.middleware.odyssey.reactive.messaging.core.cdiextension;

import com.amadeus.middleware.odyssey.reactive.messaging.core.Async;

public class CDIAsync<T> implements Async<T> {

  private Class<T> clazz;

  public CDIAsync(Class<T> clazz) {
    this.clazz = clazz;
  }

  @Override
  public T get() {
    return MessageScopedContext.getInstance()
        .get(clazz);
  }
}