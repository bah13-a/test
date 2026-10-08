package tn.vas;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tn.vas.domain.Enums.MtStatus;
import tn.vas.service.*;

class UnitTests {
    @Test
    void msisdnNormalization() {
        assertEquals("+21698123456", Text.normalizeMsisdn("98 123 456"));
        assertEquals("+21698123456", Text.normalizeMsisdn("0021698123456"));
        assertEquals("+21698123456", Text.normalizeMsisdn("21698123456"));
        assertEquals("+21698123456", Text.normalizeMsisdn("+216 98.123.456"));
        assertNull(Text.normalizeMsisdn("12345"));
        assertNull(Text.normalizeMsisdn(null));
    }

    @Test
    void segmentsGsmAndUnicode() {
        assertEquals(new Text.Sizing(Text.Encoding.GSM7, 1), Text.size("a".repeat(160)));
        assertEquals(new Text.Sizing(Text.Encoding.GSM7, 2), Text.size("a".repeat(161)));
        assertEquals(new Text.Sizing(Text.Encoding.GSM7, 2), Text.size("a".repeat(306)));
        assertEquals(new Text.Sizing(Text.Encoding.GSM7, 3), Text.size("a".repeat(307)));
        assertEquals(2, Text.size("a".repeat(80) + "€".repeat(41)).segments()); // € = 2 unités → 162
        assertEquals(new Text.Sizing(Text.Encoding.UCS2, 1), Text.size("مرحبا"));
        assertEquals(new Text.Sizing(Text.Encoding.UCS2, 1), Text.size("م".repeat(70)));
        assertEquals(new Text.Sizing(Text.Encoding.UCS2, 2), Text.size("م".repeat(71)));
        assertEquals(new Text.Sizing(Text.Encoding.UCS2, 3), Text.size("م".repeat(135)));
    }

    @Test
    void dlrMapping() {
        assertEquals(MtStatus.DELIVERED, DlrMapper.map("DELIVRD"));
        assertEquals(MtStatus.UNDELIVERABLE, DlrMapper.map("undeliv"));
        assertEquals(MtStatus.EXPIRED, DlrMapper.map("EXPIRED"));
        assertEquals(MtStatus.REJECTED, DlrMapper.map("REJECTD"));
        assertEquals(MtStatus.UNKNOWN, DlrMapper.map("???"));
        assertTrue(DlrMapper.isFinal(MtStatus.DELIVERED));
        assertFalse(DlrMapper.isFinal(MtStatus.SUBMITTED));
    }

    @Test
    void revenueSplitAlwaysSumsToGross() {
        var s = RevenueSplit.compute(new BigDecimal("1.000"), new BigDecimal("19"), new BigDecimal("40"), new BigDecimal("30"));
        assertEquals(new BigDecimal("1.000"),
                s.taxes().add(s.operatorShare()).add(s.partnerShare()).add(s.providerShare()));
        assertEquals(new BigDecimal("0.160"), s.taxes());
        assertEquals(new BigDecimal("0.336"), s.operatorShare());
    }

    @Test
    void webhookSignatureIsStable() {
        assertEquals(WebhookService.sign("k", "1.{}"), WebhookService.sign("k", "1.{}"));
        assertNotEquals(WebhookService.sign("k", "1.{}"), WebhookService.sign("k2", "1.{}"));
    }
}
