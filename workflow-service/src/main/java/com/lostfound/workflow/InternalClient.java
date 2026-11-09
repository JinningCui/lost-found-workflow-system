package com.lostfound.workflow;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InternalClient {
    private final CatalogService catalog;
    private final String token;
    private final AtomicInteger next = new AtomicInteger();
    private final RestClient client = RestClient.create();

    public InternalClient(CatalogService catalog, @Value("${lostfound.internal-token}") String token) {
        this.catalog = catalog;
        this.token = token;
    }

    public Object get(String service, String operation, String path) {
        return exchange(service, operation, HttpMethod.GET, path, null);
    }

    public Object post(String service, String operation, String path, Object body) {
        return exchange(service, operation, HttpMethod.POST, path, body);
    }

    public Object exchange(String service, String operation, HttpMethod method, String path, Object body) {
        catalog.requireEnabled(service, operation);
        List<ServiceInstance> instances = catalog.instances(service);
        if (instances.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务无在线实例: " + service);
        ServiceInstance instance = instances.get(Math.floorMod(next.getAndIncrement(), instances.size()));
        URI uri = instance.getUri().resolve(path);
        try {
            RestClient.RequestBodySpec request = client.method(method).uri(uri)
                    .header("X-Internal-Token", token).contentType(MediaType.APPLICATION_JSON);
            if (body != null) request.body(body);
            return request.retrieve().body(Object.class);
        } catch (RestClientResponseException ex) {
            throw new ResponseStatusException(ex.getStatusCode(), "服务拒绝请求: " + service + " " + path + " — " + ex.getResponseBodyAsString(), ex);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "服务调用失败: " + service + " " + path + " — " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "下游服务响应格式错误");
    }

    public static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
