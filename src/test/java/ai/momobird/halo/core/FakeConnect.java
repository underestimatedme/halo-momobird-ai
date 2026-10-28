package ai.momobird.halo.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * MomoBird's connect API in memory, shaped like {@code hexo-momobird-ai/dev/mock-connect.js}:
 * offset cursors, 500 per page, created / updated / unchanged / rejected results.
 */
public class FakeConnect implements ConnectApi {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What MomoBird holds, by external id, in insertion order. */
    public final Map<String, PostPayload> remote = new LinkedHashMap<>();

    public String kind = "halo";
    public String siteUrl = FakePosts.SITE;
    public String widgetReason = "not_published";
    public String sourceId = "src-halo";
    public String projectName = "我的博客";
    public String planStatus = "trial";
    public boolean planActive = true;
    public String planEnds = "2026-11-02T00:00:00Z";
    public String publicId = "pub_1";
    public boolean planExpired;
    public RuntimeException upsertFailure;
    public RuntimeException statusFailure;
    public boolean widgetNotReady;
    public final Map<String, String> rejectIds = new HashMap<>();
    public final Set<String> tooLargeIds = new HashSet<>();
    /** Ids a delete call answers nothing for (a server that leaves them out of the results). */
    public final Set<String> deleteNoResult = new HashSet<>();
    /** Keeps handing back the same cursor: a broken server. */
    public boolean repeatCursor;
    /** Called with each upsert batch before it is answered. */
    public Consumer<List<PostPayload>> onUpsert = batch -> { };
    public long upsertDelayMillis;

    public final List<List<PostPayload>> upserts = new CopyOnWriteArrayList<>();
    public final List<List<String>> deletes = new CopyOnWriteArrayList<>();
    public final List<String> cursors = new CopyOnWriteArrayList<>();
    public final AtomicInteger statusCalls = new AtomicInteger();
    public final AtomicInteger enableCalls = new AtomicInteger();
    public final AtomicInteger inFlight = new AtomicInteger();
    public final AtomicInteger maxInFlight = new AtomicInteger();
    public final Set<String> threads = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public FakeConnect seed(String... ids) {
        for (String id : ids) {
            remote.put(id, null);
        }
        return this;
    }

    public FakeConnect seedPosts(String... names) {
        for (String name : names) {
            remote.put(PostId.of(name), null);
        }
        return this;
    }

    private void enter() {
        threads.add(Thread.currentThread().getName());
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
    }

    private void leave() {
        inFlight.decrementAndGet();
    }

    @Override
    public JsonNode status() {
        enter();
        try {
            statusCalls.incrementAndGet();
            if (statusFailure != null) {
                throw statusFailure;
            }
            ObjectNode data = JSON.createObjectNode();
            data.putObject("project").put("id", "p-halo").put("name", projectName);
            ObjectNode plan = data.putObject("entitlement");
            plan.put("status", planStatus).put("active", planActive);
            plan.put("trial_ends_at", planEnds).putNull("pro_ends_at");
            ObjectNode source = data.putObject("source");
            source.put("document_count", remote.size()).put("ready_document_count", remote.size());
            source.put("id", sourceId);
            source.put("kind", kind);
            source.put("name", "Halo 测试来源");
            source.put("site_url", siteUrl);
            ObjectNode widget = data.putObject("widget");
            widget.put("public_id", publicId);
            widget.put("available", "ok".equals(widgetReason));
            widget.put("reason", widgetReason);
            return data;
        } finally {
            leave();
        }
    }

    @Override
    public JsonNode listPosts(String cursor) {
        enter();
        try {
            cursors.add(cursor == null ? "" : cursor);
            int offset = cursor == null || cursor.isEmpty() ? 0 : Integer.parseInt(cursor);
            List<String> ids = new ArrayList<>(remote.keySet());
            ObjectNode data = JSON.createObjectNode();
            ArrayNode items = data.putArray("items");
            int end = Math.min(ids.size(), offset + ConnectClient.PAGE_SIZE);
            for (int i = offset; i < end; i++) {
                items.addObject().put("external_id", ids.get(i)).put("document_id", "doc-" + ids.get(i));
            }
            if (repeatCursor) {
                data.put("next_cursor", "7");
            } else if (end < ids.size()) {
                data.put("next_cursor", String.valueOf(end));
            } else {
                data.putNull("next_cursor");
            }
            return data;
        } finally {
            leave();
        }
    }

    @Override
    public JsonNode upsert(List<PostPayload> posts) {
        enter();
        try {
            if (upsertDelayMillis > 0) {
                try {
                    Thread.sleep(upsertDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            upserts.add(List.copyOf(posts));
            onUpsert.accept(posts);
            if (posts.isEmpty() || posts.size() > 20) {
                throw ConnectError.http(400, "VALIDATION_FAILED");
            }
            if (planExpired) {
                throw ConnectError.http(402, "PLAN_REQUIRED");
            }
            if (upsertFailure != null) {
                throw upsertFailure;
            }
            for (PostPayload post : posts) {
                if (tooLargeIds.contains(post.externalId())) {
                    throw ConnectError.http(413, null);
                }
            }
            ObjectNode data = JSON.createObjectNode();
            ArrayNode results = data.putArray("results");
            for (PostPayload post : posts) {
                String id = post.externalId();
                if (rejectIds.containsKey(id)) {
                    results.addObject().put("external_id", id).putNull("document_id")
                        .put("status", "rejected").put("error_code", rejectIds.get(id));
                    continue;
                }
                String outcome;
                if (!remote.containsKey(id)) {
                    outcome = "created";
                } else {
                    outcome = post.equals(remote.get(id)) ? "unchanged" : "updated";
                }
                remote.put(id, post);
                results.addObject().put("external_id", id).put("document_id", "doc-" + id)
                    .put("status", outcome).putNull("error_code");
            }
            return data;
        } finally {
            leave();
        }
    }

    @Override
    public JsonNode delete(List<String> ids) {
        enter();
        try {
            deletes.add(List.copyOf(ids));
            if (ids.isEmpty() || ids.size() > 100) {
                throw ConnectError.http(400, "VALIDATION_FAILED");
            }
            ObjectNode data = JSON.createObjectNode();
            ArrayNode results = data.putArray("results");
            for (String id : ids) {
                if (deleteNoResult.contains(id)) {
                    continue;
                }
                boolean had = remote.containsKey(id);
                remote.remove(id);
                results.addObject().put("external_id", id).put("status", had ? "deleted" : "not_found");
            }
            return data;
        } finally {
            leave();
        }
    }

    @Override
    public JsonNode enableWidget() {
        enter();
        try {
            enableCalls.incrementAndGet();
            if (planExpired) {
                throw ConnectError.http(402, "PLAN_REQUIRED");
            }
            if (widgetNotReady) {
                throw ConnectError.http(409, "WIDGET_NOT_READY");
            }
            widgetReason = "ok";
            ObjectNode data = JSON.createObjectNode();
            data.put("public_id", "pub_1").put("available", true).put("reason", "ok");
            return data;
        } finally {
            leave();
        }
    }

    /** Every id sent in a delete call, flattened. */
    public List<String> deletedIds() {
        List<String> all = new ArrayList<>();
        deletes.forEach(all::addAll);
        return all;
    }

    /** Every id pushed, flattened. */
    public List<String> pushedIds() {
        List<String> all = new ArrayList<>();
        upserts.forEach(batch -> batch.forEach(p -> all.add(p.externalId())));
        return all;
    }
}
