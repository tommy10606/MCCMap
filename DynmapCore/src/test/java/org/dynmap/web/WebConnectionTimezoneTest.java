package org.dynmap.web;
import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
class WebConnectionTimezoneTest {
    @Test void systemAndMissingSettingsUseTheHostTimezone() {
        ZoneId host=ZoneId.of("Asia/Kolkata");
        for(String setting:new String[]{null,"","system"," SYSTEM "}) {
            assertEquals(host,WebConnectionLog.resolveTimezone(setting,host,m->fail(m)));
        }
        assertEquals("2026-11-07T19:30:00+05:30",WebConnectionLog.timestamp(Instant.parse("2026-11-07T14:00:00Z"),host));
    }
    @Test void configuredTimezoneUsesTheCorrectDateOffsetAndDaylightSavingRules() {
        ZoneId zone=WebConnectionLog.resolveTimezone("America/New_York",ZoneOffset.UTC,m->fail(m));
        assertEquals("2026-07-07T08:00:00-04:00",WebConnectionLog.timestamp(Instant.parse("2026-07-07T12:00:00Z"),zone));
        assertEquals("2026-11-07T09:00:00-05:00",WebConnectionLog.timestamp(Instant.parse("2026-11-07T14:00:00Z"),zone));
        assertEquals("2026-11-06T21:00:00-05:00",WebConnectionLog.timestamp(Instant.parse("2026-11-07T02:00:00Z"),zone));
    }
    @Test void utcOverrideIsIndependentOfTheHost() {
        assertEquals("2026-11-07T14:00:00Z",WebConnectionLog.timestamp(Instant.parse("2026-11-07T14:00:00Z"),WebConnectionLog.resolveTimezone("UTC",ZoneId.of("America/Chicago"),m->fail(m))));
    }
    @Test void invalidTimezoneFallsBackWithoutEchoingUnsafeConfiguration() {
        var warnings=new ArrayList<String>();ZoneId host=ZoneId.of("America/Chicago");
        assertEquals(host,WebConnectionLog.resolveTimezone("${jndi:ldap://bad}\nFORGED",host,warnings::add));
        assertEquals(1,warnings.size());assertFalse(warnings.getFirst().contains("\n"));assertFalse(warnings.getFirst().contains("${"));
    }
    @Test void connectionsContainAnUnambiguousLocalDateAndOffset() {
        assertEquals("2026-11-07T09:00:00-05:00 CONNECT 127.0.0.1:43210\n",WebConnectionLog.format(Instant.parse("2026-11-07T14:00:00Z"),new InetSocketAddress("127.0.0.1",43210),ZoneId.of("America/New_York")));
    }
}
