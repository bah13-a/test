package tn.vas.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tn.vas.config.VasProperties;
import tn.vas.config.VasProperties.*;
import tn.vas.domain.MtMessage;
import tn.vas.domain.Operator;

/** Classification des réponses de l'API HTTP Jasmin (codes constatés sur Jasmin 0.10.13 réel). */
class JasminHttpGatewayTests {
    private JasminHttpGateway gateway(MockRestServiceServer[] holder) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        holder[0] = server;
        var props = new VasProperties(new Jasmin("http://jasmin:1401", "Jasmin-Zx9-0001", false), new Callback("secret-callback-value-0123456"), new Retry(5, 30), List.of(), "rabbit", "redis",
                "http://app.vas.internal:8080", 30, true, "t", List.of(), "create-only", new Retention(0, 0, 0, 0), null);
        return new JasminHttpGateway(builder.build(), props);
    }

    private MtMessage mt() {
        var op = new Operator();
        op.setCode("TT");
        var m = new MtMessage();
        m.setOperator(op); m.setMsisdn("+21698123456"); m.setSender("85500"); m.setContent("Bonjour"); m.setEncoding("GSM7");
        m.setCorrelationId("c-1"); m.setPriority(tn.vas.domain.Enums.Priority.TRANSACTIONAL);
        return m;
    }

    private SmsGateway.SendResult send(org.springframework.test.web.client.ResponseCreator rc) {
        var h = new MockRestServiceServer[1];
        var g = gateway(h);
        h[0].expect(requestTo("http://jasmin:1401/send")).andRespond(rc);
        return g.send(mt());
    }

    @Test
    void acceptedMessageReturnsJasminId() {
        var r = send(withSuccess("Success \"abc-123\"", org.springframework.http.MediaType.TEXT_PLAIN));
        assertTrue(r.accepted());
        assertEquals("abc-123", r.smscMessageId());
    }

    @Test
    void noBoundConnectorIsRetryableNotLost() {
        var r = send(withStatus(HttpStatus.PRECONDITION_FAILED).body("Error \"Failover route has no bound connectors\""));
        assertFalse(r.accepted());
        assertTrue(r.retryable(), "412 : liaisons coupées, le MT doit être réessayé et non perdu");
    }

    @Test
    void serverErrorsAndThrottlingAreRetryableButAuthIsNot() {
        assertTrue(send(withStatus(HttpStatus.SERVICE_UNAVAILABLE)).retryable());
        assertTrue(send(withStatus(HttpStatus.TOO_MANY_REQUESTS)).retryable());
        assertFalse(send(withStatus(HttpStatus.FORBIDDEN).body("Error \"Authentication failure\"")).retryable());
        assertFalse(send(withStatus(HttpStatus.BAD_REQUEST)).retryable());
    }
}
