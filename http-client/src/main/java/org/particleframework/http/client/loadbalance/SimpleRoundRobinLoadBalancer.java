/*
 * Copyright 2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.particleframework.http.client.loadbalance;

import org.particleframework.core.async.publisher.Publishers;
import org.particleframework.core.util.CollectionUtils;
import org.particleframework.core.util.StringUtils;
import org.particleframework.discovery.ServiceInstance;
import org.particleframework.http.client.ServiceInstanceLoadBalancer;
import org.particleframework.http.client.exceptions.HttpClientException;
import org.reactivestreams.Publisher;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A simple {@link ServiceInstanceLoadBalancer} that uses round robin load balancing against a pre-supplied list of
 * {@link ServiceInstance}
 *
 * @author Graeme Rocher
 * @since 1.0
 */
public class SimpleRoundRobinLoadBalancer implements ServiceInstanceLoadBalancer {

    private final AtomicInteger index = new AtomicInteger(0);
    private final List<ServiceInstance> servers;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final String serviceId;

    public SimpleRoundRobinLoadBalancer(String serviceId, List<ServiceInstance> servers) {
        if(StringUtils.isEmpty(serviceId)) {
            throw new IllegalArgumentException("Service ID cannot be blank");
        }
        if(CollectionUtils.isEmpty(servers)) {
            throw new IllegalArgumentException("At least one server is required");
        }
        this.servers = servers;
        this.serviceId = serviceId;
    }

    @Override
    public Publisher<URL> select(Object discriminator) {
        // Ideally should be rewritten without the use of locks
        Lock lock = this.lock.readLock();
        lock.lock();

        try {
            int i = index.getAndAccumulate(servers.size(), (cur, n) -> cur >= n - 1 ? 0 : cur + 1);
            ServiceInstance instance = servers.get(i);
            try {
                return Publishers.just(instance.getURI().toURL());
            } catch (MalformedURLException e) {
                return Publishers.just(new HttpClientException("Invalid service URI: " + instance.getURI()));
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String getID() {
        return serviceId;
    }

    /**
     * Updates the backing server list
     * @param servers The servers
     */
    @Override
    public void update(List<ServiceInstance> servers) {
        if(CollectionUtils.isEmpty(servers)) {
            throw new IllegalArgumentException("At least one server is required");
        }
        Lock lock = this.lock.writeLock();
        lock.lock();
        try {
            index.set(0);
            this.servers.clear();
            this.servers.addAll(servers);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<ServiceInstance> getInstances() {
        return Collections.unmodifiableList(servers);
    }
}