package com.aash.mailguard.dns;

import org.springframework.stereotype.Component;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

@Component
public class DnsLookupService {
    private final Resolver resolver;

    public DnsLookupService() {
        this(Lookup.getDefaultResolver());
    }

    DnsLookupService(Resolver resolver) {
        this.resolver = resolver;
    }

    public Boolean hasMxRecord(String domain) {
        if (domain == null) {
            return null;
        }

        try {
            Lookup lookup = new Lookup(Name.fromString(domain, Name.root), Type.MX);
            lookup.setResolver(resolver);
            Record[] records = lookup.run();
            if (lookup.getResult() == Lookup.SUCCESSFUL) {
                for (Record record : records) {
                    if (!((MXRecord) record).getTarget().equals(Name.root)) {
                        return true;
                    }
                }
                return false;
            }
            if (lookup.getResult() == Lookup.HOST_NOT_FOUND || lookup.getResult() == Lookup.TYPE_NOT_FOUND) {
                return false;
            }
            return null;
        } catch (TextParseException e) {
            return null;
        }
    }
}
