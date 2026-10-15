package com.aash.mailguard.dns;

import org.junit.jupiter.api.Test;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Section;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DnsLookupServiceTest {

    @Test
    void mxAnswerIsObserved() throws Exception {
        Resolver resolver = mock(Resolver.class);
        when(resolver.send(any(Message.class))).thenAnswer(invocation -> {
            Message query = invocation.getArgument(0);
            Message response = reply(query, Rcode.NOERROR);
            response.addRecord(new MXRecord(query.getQuestion().getName(), DClass.IN, 60, 10,
                    Name.fromString("mail.fixture.test.")), Section.ANSWER);
            return response;
        });

        assertEquals(true, new DnsLookupService(resolver).hasMxRecord("present.fixture.test."));
    }

    @Test
    void missingMxDoesNotImplyMailboxInvalidity() throws Exception {
        Resolver resolver = mock(Resolver.class);
        when(resolver.send(any(Message.class))).thenAnswer(invocation -> reply(invocation.getArgument(0), Rcode.NOERROR));

        assertEquals(false, new DnsLookupService(resolver).hasMxRecord("missing.fixture.test."));
    }

    @Test
    void nxdomainIsAbsent() throws Exception {
        Resolver resolver = mock(Resolver.class);
        when(resolver.send(any(Message.class))).thenAnswer(invocation -> reply(invocation.getArgument(0), Rcode.NXDOMAIN));

        assertEquals(false, new DnsLookupService(resolver).hasMxRecord("nxdomain.fixture.test."));
    }

    @Test
    void dnsFailureDoesNotImplyMissingMx() throws Exception {
        Resolver resolver = mock(Resolver.class);
        when(resolver.send(any(Message.class))).thenThrow(new InterruptedIOException("timeout"));

        assertNull(new DnsLookupService(resolver).hasMxRecord("timeout.fixture.test."));
    }

    @Test
    void resolverIOExceptionIsUnknownNotMissingMx() throws Exception {
        Resolver resolver = mock(Resolver.class);
        when(resolver.send(any(Message.class))).thenThrow(new IOException("temporary resolver failure"));

        assertNull(new DnsLookupService(resolver).hasMxRecord("temporary.fixture.test."));
    }

    @Test
    void serverFailureIsUnknown() throws Exception {
        Resolver resolver = mock(Resolver.class);
        when(resolver.send(any(Message.class))).thenAnswer(invocation -> reply(invocation.getArgument(0), Rcode.SERVFAIL));

        assertNull(new DnsLookupService(resolver).hasMxRecord("servfail.fixture.test."));
    }

    @Test
    void malformedDomainIsUnknownWithoutDns() {
        Resolver resolver = mock(Resolver.class);

        assertNull(new DnsLookupService(resolver).hasMxRecord("bad..fixture.test"));
        verifyNoInteractions(resolver);
    }

    @Test
    void nullDomainIsUnknownWithoutDns() {
        Resolver resolver = mock(Resolver.class);

        assertNull(new DnsLookupService(resolver).hasMxRecord(null));
        verifyNoInteractions(resolver);
    }


    @Test
    void nullMxExplicitlyDeclinesMail() throws Exception {
        Resolver resolver = mock(Resolver.class);
        when(resolver.send(any(Message.class))).thenAnswer(invocation -> {
            Message query = invocation.getArgument(0);
            Message response = reply(query, Rcode.NOERROR);
            response.addRecord(new MXRecord(query.getQuestion().getName(), DClass.IN, 60, 0, Name.root), Section.ANSWER);
            return response;
        });

        assertEquals(false, new DnsLookupService(resolver).hasMxRecord("nullmx.fixture.test."));
    }

    @Test
    void searchSuffixCannotSupplyMxForAnotherDomain() throws Exception {
        Name domain = Name.fromString("unmatched.fixture.test.");
        Name suffixed = Name.fromString("unmatched.fixture.test.corp.fixture.test.");
        List<Name> originalSearchPath = Lookup.getDefaultSearchPath();
        List<Name> queried = new ArrayList<>();
        Resolver resolver = mock(Resolver.class);
        when(resolver.send(any(Message.class))).thenAnswer(invocation -> {
            Message query = invocation.getArgument(0);
            Name queriedName = query.getQuestion().getName();
            queried.add(queriedName);
            Message response = reply(query, queriedName.equals(suffixed) ? Rcode.NOERROR : Rcode.NXDOMAIN);
            if (queriedName.equals(suffixed)) {
                response.addRecord(new MXRecord(suffixed, DClass.IN, 60, 10,
                        Name.fromString("mail.corp.fixture.test.")), Section.ANSWER);
            }
            return response;
        });

        try {
            Lookup.setDefaultSearchPath("corp.fixture.test.");
            assertEquals(false, new DnsLookupService(resolver).hasMxRecord("unmatched.fixture.test"));
            assertEquals(List.of(domain), queried);
        } finally {
            if (originalSearchPath == null) {
                Lookup.setDefaultSearchPath((String[]) null);
            } else {
                Lookup.setDefaultSearchPath(originalSearchPath);
            }
        }
    }

    private static Message reply(Message query, int rcode) {
        Message response = new Message(query.getHeader().getID());
        response.addRecord(query.getQuestion(), Section.QUESTION);
        response.getHeader().setRcode(rcode);
        return response;
    }
}
