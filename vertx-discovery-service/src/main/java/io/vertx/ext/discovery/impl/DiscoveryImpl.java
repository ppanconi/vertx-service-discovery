/*
 * Copyright (c) 2011-2016 The original author or authors
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *      The Eclipse Public License is available at
 *      http://www.eclipse.org/legal/epl-v10.html
 *
 *      The Apache License v2.0 is available at
 *      http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.discovery.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.discovery.*;
import io.vertx.ext.discovery.spi.DiscoveryBackend;
import io.vertx.ext.discovery.spi.DiscoveryBridge;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

/**
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
public class DiscoveryImpl implements DiscoveryService {

  private final Vertx vertx;
  private final String announce;
  private final String usage;
  private final DiscoveryBackend backend;

  private final Set<DiscoveryBridge> bridges = new CopyOnWriteArraySet<>();
  private final Set<ServiceReference> bindings = new CopyOnWriteArraySet<>();
  private final static Logger LOGGER = LoggerFactory.getLogger(DiscoveryImpl.class.getName());
  private final String id;


  public DiscoveryImpl(Vertx vertx, DiscoveryOptions options) {
    this.vertx = vertx;
    this.announce = options.getAnnounceAddress();
    this.usage = options.getUsageAddress();

    this.backend = getBackend(options.getBackendConfiguration().getString("backend-name", null));
    this.backend.init(vertx, options.getBackendConfiguration());

    this.id = options.getName() != null ? options.getName() : getNodeId(vertx);

  }

  private String getNodeId(Vertx vertx) {
    if (vertx.isClustered()) {
      return ((VertxInternal) vertx).getNodeID();
    } else {
      return "localhost";
    }
  }

  private DiscoveryBackend getBackend(String maybeName) {
    ServiceLoader<DiscoveryBackend> backends = ServiceLoader.load(DiscoveryBackend.class);
    Iterator<DiscoveryBackend> iterator = backends.iterator();

    if (maybeName == null) {
      if (!iterator.hasNext()) {
        return new DefaultDiscoveryBackend();
      } else {
        return iterator.next();
      }
    }

    if (maybeName.equals(DefaultDiscoveryBackend.class.getName())) {
      return new DefaultDiscoveryBackend();
    }

    // We have a name
    while (iterator.hasNext()) {
      DiscoveryBackend backend = iterator.next();
      if (backend.name().equals(maybeName)) {
        return backend;
      }
    }

    throw new IllegalStateException("Cannot find the discovery backend implementation with name " + maybeName + " in " +
        "the classpath");
  }


  @Override
  public ServiceReference getReference(Record record) {
    return getReferenceWithConfiguration(record, new JsonObject());
  }

  @Override
  public ServiceReference getReferenceWithConfiguration(Record record, JsonObject configuration) {
    ServiceReference reference = ServiceTypes.get(record).get(vertx, this, record, configuration);
    bindings.add(reference);
    sendBindEvent(reference);
    return reference;
  }

  private void sendBindEvent(ServiceReference reference) {
    if (usage == null) {
      return;
    }
    vertx.eventBus().publish(usage, new JsonObject()
        .put("type", "bind")
        .put("record", reference.record().toJson())
        .put("id", id));
  }

  @Override
  public boolean release(ServiceReference reference) {
    boolean removed = bindings.remove(reference);
    reference.release();
    sendUnbindEvent(reference);
    return removed;
  }

  private void sendUnbindEvent(ServiceReference reference) {
    if (usage == null) {
      return;
    }
    vertx.eventBus().publish(usage, new JsonObject()
        .put("type", "release")
        .put("record", reference.record().toJson())
        .put("id", id));
  }

  @Override
  public DiscoveryService registerDiscoveryBridge(DiscoveryBridge bridge, JsonObject configuration) {
    JsonObject conf;
    if (configuration == null) {
      conf = new JsonObject();
    } else {
      conf = configuration;
    }
    vertx.<Void>executeBlocking(
        future -> {
          bridge.start(vertx, this, conf, (ar) -> {
            if (ar.failed()) {
              future.fail(ar.cause());
            } else {
              bridges.add(bridge);
              future.complete();
            }
          });
        },
        ar -> {
          if (ar.failed()) {
            LOGGER.error("Cannot start the discovery bridge " + bridge, ar.cause());
          } else {
            LOGGER.info("Discovery bridge " + bridge + " started");
          }
        }
    );
    return this;
  }

  @Override
  public void close() {
    LOGGER.info("Stopping discovery service");
    for (DiscoveryBridge bridge : bridges) {
      bridge.stop(vertx, this);
    }

    bindings.forEach(ServiceReference::release);
    bindings.clear();
  }

  @Override
  public void publish(Record record, Handler<AsyncResult<Record>> resultHandler) {
    backend.store(record.setStatus(Status.UP), resultHandler);
    Record announcedRecord = new Record(record);
    announcedRecord
        .setRegistration(null)
        .setStatus(Status.UP);
    vertx.eventBus().publish(announce, announcedRecord.toJson());
  }

  @Override
  public void unpublish(String id, Handler<AsyncResult<Void>> resultHandler) {
    backend.remove(id, record -> {
      if (record.failed()) {
        resultHandler.handle(Future.failedFuture(record.cause()));
        return;
      }
      Record announcedRecord = new Record(record.result());
      announcedRecord
          .setRegistration(null)
          .setStatus(Status.DOWN);
      vertx.eventBus().publish(announce, announcedRecord.toJson());
      resultHandler.handle(Future.succeededFuture());
    });

  }

  @Override
  public void getRecord(JsonObject filter,
                        Handler<AsyncResult<Record>> resultHandler) {
    if (filter.getString("status") == null) {
      filter.put("status", Status.UP.name());
    }
    backend.getRecords(list -> {
      if (list.failed()) {
        resultHandler.handle(Future.failedFuture(list.cause()));
      } else {
        Optional<Record> any = list.result().stream()
            .filter(record -> record.match(filter))
            .findAny();
        if (any.isPresent()) {
          resultHandler.handle(Future.succeededFuture(any.get()));
        } else {
          resultHandler.handle(Future.succeededFuture(null));
        }
      }
    });
  }

  @Override
  public void getRecords(JsonObject filter, Handler<AsyncResult<List<Record>>> resultHandler) {
    if (filter.getValue("status") == null) {
      filter.put("status", Status.UP.name());
    }
    backend.getRecords(list -> {
      if (list.failed()) {
        resultHandler.handle(Future.failedFuture(list.cause()));
      } else {
        List<Record> match = list.result().stream()
            .filter(record -> record.match(filter)).collect(Collectors.toList());
        resultHandler.handle(Future.succeededFuture(match));
      }
    });
  }

  @Override
  public void update(Record record, Handler<AsyncResult<Record>> resultHandler) {
    backend.update(record, ar -> {
      if (ar.failed()) {
        resultHandler.handle(Future.failedFuture(ar.cause()));
      } else {
        resultHandler.handle(Future.succeededFuture(record));
      }
    });

    Record announcedRecord = new Record(record);
    vertx.eventBus().publish(announce, announcedRecord.toJson());
  }

  @Override
  public Set<ServiceReference> bindings() {
    return new HashSet<>(bindings);
  }

  /**
   * Checks whether the reference is hold by this discovery service. If so, remove it from the list of bindings and
   * fire the "release" event.
   *
   * @param reference the reference
   */
  public void unbind(ServiceReference reference) {
    if (bindings.remove(reference)) {
      sendUnbindEvent(reference);
    }
  }
}
