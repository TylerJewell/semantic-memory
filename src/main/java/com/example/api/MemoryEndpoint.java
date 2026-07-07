package com.example.api;

import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpException;
import com.example.application.FlureeClient;
import com.example.application.conflict.EntityResolver;
import com.example.application.conflict.Reconciler;
import com.example.application.conflict.Resolution;
import com.example.application.conflict.ResolutionCascade;
import com.example.application.ingest.FlureeTripleStore;
import com.example.application.ingest.IngestGate;
import com.example.application.ingest.IngestReport;
import com.example.application.sync.SourceLayerResolver;
import com.example.domain.Layer;
import com.example.domain.ProvenanceEnvelope;
import com.example.domain.Triple;
import com.example.application.GraphExtractorAgent;
import com.example.domain.KnowledgeGraph;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/api")
public class MemoryEndpoint extends AbstractHttpEndpoint {

  private final ComponentClient client;

  public MemoryEndpoint(ComponentClient client) {
    this.client = client;
  }

  public record RememberRequest(String text) {}
  public record RememberResponse(KnowledgeGraph graph, String report) {}
  public record StatsResponse(int chunks, int entities, int commits) {}
  public record RecentResponse(List<String> chunks) {}
  public record ForgetResponse(String status) {}

  // --- /sync: wire the conflict-resolution engine to the live Fluree store ---
  public record SyncFact(String subject, String predicate, String object, String source, Double conf) {}
  public record SyncFile(String path, String content) {}
  public record SyncRequest(
      String authoredGlob,
      String corpusGlob,
      List<SyncFact> authored,
      List<SyncFact> corpus,
      List<SyncFile> vaultFiles,
      List<SyncFile> corpusUploads) {}
  public record SyncResponse(
      int authored, int newCount, int corroborating, int conflicting, int suppressed) {}

  @Post("/sync")
  public SyncResponse sync(SyncRequest req) {
    // 1. Resolver rejects overlapping authored/corpus globs at construction (EC-043).
    try {
      new SourceLayerResolver(req.authoredGlob(), req.corpusGlob());
    } catch (IllegalArgumentException e) {
      throw HttpException.error(StatusCodes.CONFLICT, "source glob overlap: " + e.getMessage());
    }

    // 2. Vault-leak guard: a corpus upload must not be a copy of a vault file (EC-043).
    List<String> vaultContents = new ArrayList<>();
    if (req.vaultFiles() != null) {
      for (SyncFile f : req.vaultFiles()) {
        vaultContents.add(f.content());
      }
    }
    if (req.corpusUploads() != null) {
      for (SyncFile up : req.corpusUploads()) {
        if (SourceLayerResolver.isVaultLeak(up.content(), vaultContents)) {
          throw HttpException.error(StatusCodes.UNPROCESSABLE_ENTITY, "vault leak: " + up.path());
        }
      }
    }

    // 3. Persist authored facts directly; run corpus facts through the ingest gate.
    FlureeTripleStore store = new FlureeTripleStore();
    EntityResolver resolver = new EntityResolver(EntityResolver.Mode.ALIAS_AWARE, Map.of());
    IngestReport report = new IngestReport();
    Instant now = Instant.now();

    int authoredCount = 0;
    if (req.authored() != null) {
      for (SyncFact f : req.authored()) {
        Triple t =
            new Triple(
                f.subject(),
                f.predicate(),
                f.object(),
                new ProvenanceEnvelope(Layer.AUTHORED, f.source(), now, 1.0),
                true);
        store.add(t);
        authoredCount++;
      }
    }
    if (req.corpus() != null) {
      for (SyncFact f : req.corpus()) {
        double conf = f.conf() == null ? 1.0 : f.conf();
        Triple t =
            new Triple(
                f.subject(),
                f.predicate(),
                f.object(),
                new ProvenanceEnvelope(Layer.DERIVED, f.source(), now, conf),
                true);
        IngestGate.ingest(t, store, resolver, report);
      }
    }

    return new SyncResponse(
        authoredCount,
        report.newCount(),
        report.corroborating(),
        report.conflicting(),
        report.suppressed());
  }

  // --- read side: reconcile persisted assertions into disagreements + conflicts ---
  public record DisagreementsResponse(List<Reconciler.Disagreement> disagreements) {}
  public record ConflictsResponse(List<Reconciler.Conflict> conflicts) {}

  @Get("/disagreements")
  public DisagreementsResponse disagreements() {
    Reconciler.Result r = Reconciler.reconcile(FlureeClient.queryAllEnvelopes());
    return new DisagreementsResponse(r.disagreements());
  }

  @Get("/conflicts")
  public ConflictsResponse conflicts() {
    Reconciler.Result r = Reconciler.reconcile(FlureeClient.queryAllEnvelopes());
    return new ConflictsResponse(r.conflicts());
  }

  // --- primary read: the served (Model-1) value for a subject + predicate, plus provenance ---
  public record FactCandidate(String object, String layer, String source) {}
  public record FactResponse(
      String subject,
      String predicate,
      String state,
      String served,
      String servedLayer,
      String resolvedBy,
      String source,
      Integer flaggedCount,
      List<FactCandidate> candidates,
      String reason) {}

  @Get("/fact")
  public FactResponse fact() {
    String subject = requestContext().queryParams().getString("subject").orElse("");
    String predicate = requestContext().queryParams().getString("predicate").orElse("");

    // queryEnvelopes slugs the subject/predicate itself; assertions are stored slugged.
    List<FlureeClient.Envelope> envelopes = FlureeClient.queryEnvelopes(subject, predicate);
    if (envelopes.isEmpty()) {
      return new FactResponse(subject, predicate, "UNKNOWN", null, null, null, null, null, null, null);
    }

    List<Triple> triples = new ArrayList<>();
    for (FlureeClient.Envelope e : envelopes) {
      Layer layer = "authored".equals(e.layer()) ? Layer.AUTHORED : Layer.DERIVED;
      triples.add(
          new Triple(
              slug(subject),
              slug(predicate),
              e.object(),
              new ProvenanceEnvelope(layer, e.source(), Instant.EPOCH, e.conf()),
              true));
    }

    Resolution res = ResolutionCascade.resolve(triples, List.of());
    if (res instanceof Resolution.Resolved r) {
      return new FactResponse(
          subject,
          predicate,
          "RESOLVED",
          r.winner().object(),
          layerStr(r.winner().envelope().layer()),
          r.resolvedBy(),
          r.winner().envelope().source(),
          r.flagged().size(),
          null,
          null);
    }
    Resolution.Contested c = (Resolution.Contested) res;
    List<FactCandidate> candidates = new ArrayList<>();
    for (Triple t : c.candidates()) {
      candidates.add(
          new FactCandidate(t.object(), layerStr(t.envelope().layer()), t.envelope().source()));
    }
    return new FactResponse(
        subject, predicate, "CONTESTED", null, null, null, null, null, candidates, c.reason());
  }

  // Mirror FlureeClient.slug (package-private in a different package) so Triples built here
  // carry the same slugged subject/predicate the store persisted.
  private static String slug(String s) {
    return s.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
  }

  private static String layerStr(Layer layer) {
    return layer == Layer.AUTHORED ? "authored" : "derived";
  }

  @Post("/remember")
  public RememberResponse remember(RememberRequest req) {
    KnowledgeGraph kg = client
        .forAgent()
        .inSession("remember")
        .method(GraphExtractorAgent::extract)
        .invoke(req.text());
    IngestReport report = FlureeClient.rememberAsProse(req.text(), kg);
    return new RememberResponse(kg, report.summary());
  }

  @Get("/stats")
  public StatsResponse stats() {
    FlureeClient.AssertionStats s = FlureeClient.assertionStats();
    return new StatsResponse(s.assertions(), s.subjects(), s.assertions());
  }

  @Get("/recent")
  public RecentResponse recent() {
    return new RecentResponse(FlureeClient.recentAssertions(5));
  }

  @Post("/forget")
  public ForgetResponse forget() {
    FlureeClient.forgetAll();
    return new ForgetResponse("cleared");
  }
}
