package dev.ledgerbank.rail;
import java.time.Instant;import java.util.*;import org.springframework.beans.factory.annotation.Value;import org.springframework.http.HttpHeaders;import org.springframework.stereotype.Component;import org.springframework.web.client.RestClient;
@Component class LedgerRailClient{
 private final RestClient identity,ledger;private final String secret;private String token;private Instant refreshAt=Instant.EPOCH;
 LedgerRailClient(RestClient.Builder b,@Value("${ledgerbank.identity-url}")String identityUrl,@Value("${ledgerbank.ledger-url}")String ledgerUrl,@Value("${ledgerbank.service-client-secret}")String secret){identity=b.baseUrl(identityUrl).build();ledger=b.baseUrl(ledgerUrl).build();this.secret=secret;}
 synchronized String token(){if(Instant.now().isBefore(refreshAt))return token;Token t=identity.post().uri("/api/v1/auth/service-token").body(Map.of("clientId","rail-simulator","clientSecret",secret)).retrieve().body(Token.class);if(t==null)throw new IllegalStateException("No service token");token=t.accessToken();refreshAt=Instant.now().plusSeconds(t.expiresIn()-30);return token;}
 Hold hold(UUID id,UUID account,long amount,UUID user,String cid){return ledger.post().uri("/internal/v1/holds").header(HttpHeaders.AUTHORIZATION,"Bearer "+token()).header("X-Correlation-ID",cid).body(Map.of("commandId",id,"accountId",account,"amountMinor",amount,"initiatedBySubject",user)).retrieve().body(Hold.class);}
 Journal deposit(UUID id,UUID account,long amount,String cid){return ledger.post().uri("/internal/v1/postings/ach-deposit").header(HttpHeaders.AUTHORIZATION,"Bearer "+token()).header("X-Correlation-ID",cid).body(Map.of("commandId",id,"accountId",account,"amountMinor",amount)).retrieve().body(Journal.class);}
 Journal capture(UUID id,String cid){return ledger.post().uri("/internal/v1/holds/{reference}/capture","hold:"+id).header(HttpHeaders.AUTHORIZATION,"Bearer "+token()).header("X-Correlation-ID",cid).retrieve().body(Journal.class);}
 void release(UUID id,String cid){ledger.post().uri("/internal/v1/holds/{reference}/release","hold:"+id).header(HttpHeaders.AUTHORIZATION,"Bearer "+token()).header("X-Correlation-ID",cid).retrieve().toBodilessEntity();}
 Journal reverse(String original,UUID id,String cid){return ledger.post().uri("/internal/v1/journals/reverse").header(HttpHeaders.AUTHORIZATION,"Bearer "+token()).header("X-Correlation-ID",cid).body(Map.of("originalReference",original,"commandId",id)).retrieve().body(Journal.class);}
 record Token(String accessToken,long expiresIn){}record Hold(UUID holdId,String businessReference,String status){}record Journal(UUID journalId,String businessReference,String journalType,String description,String correlationId,Instant bookedAt){}
}

