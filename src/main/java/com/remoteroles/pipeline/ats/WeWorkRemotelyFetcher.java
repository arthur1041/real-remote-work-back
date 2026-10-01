package com.remoteroles.pipeline.ats;

import com.remoteroles.pipeline.config.IngestProperties;
import com.remoteroles.pipeline.domain.AtsType;
import com.remoteroles.pipeline.domain.FetchedPosting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WeWorkRemotely's RSS feeds — the primary supply of worldwide roles.
 *
 * <p>This source carries the site. Its {@code <region>} element states
 * "Anywhere in the World" outright, and roughly nine postings in ten carry it:
 * across the main feed and a handful of category feeds, a dozen cheap requests
 * return a couple of hundred genuinely location-independent roles. No pagination,
 * no bot challenge, and an explicit {@code <expires_at>} so postings can retire on
 * their own stated date.
 *
 * <p>Every posting is kept, including the regional minority. The classifier reads
 * {@code <region>} like any other location text and sorts them out; at this volume
 * there is nothing to gain from filtering here.
 */
@Component
public class WeWorkRemotelyFetcher implements FeedFetcher {

    private static final Logger log = LoggerFactory.getLogger(WeWorkRemotelyFetcher.class);

    private static final String MAIN_FEED = "https://weworkremotely.com/remote-jobs.rss";
    private static final String CATEGORY_FEED = "https://weworkremotely.com/categories/%s.rss";

    /**
     * Category feeds worth reading. The main feed carries only the most recent
     * postings, so the categories are what give depth — they overlap, and postings
     * are de-duplicated on guid.
     */
    private static final List<String> CATEGORIES = List.of(
            "remote-programming-jobs",
            "remote-design-jobs",
            "remote-customer-support-jobs",
            "remote-devops-sysadmin-jobs",
            "remote-sales-and-marketing-jobs",
            "remote-product-jobs",
            "remote-full-stack-programming-jobs",
            "remote-back-end-programming-jobs",
            "remote-front-end-programming-jobs");

    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME;

    private final HttpClient client;
    private final IngestProperties props;

    public WeWorkRemotelyFetcher(IngestProperties props) {
        this.props = props;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public AtsType source() {
        return AtsType.WEWORKREMOTELY;
    }

    @Override
    public List<FetchedPosting> fetch() {
        // Keyed by guid: the category feeds overlap heavily with each other and with
        // the main feed, and insertion order keeps the newest-first ordering.
        Map<String, FetchedPosting> byGuid = new LinkedHashMap<>();

        List<String> urls = new ArrayList<>();
        urls.add(MAIN_FEED);
        CATEGORIES.forEach(category -> urls.add(CATEGORY_FEED.formatted(category)));

        int failed = 0;
        for (String url : urls) {
            try {
                for (FetchedPosting posting : parseFeed(fetchXml(url))) {
                    byGuid.putIfAbsent(posting.externalId(), posting);
                }
                // Sequential with a pause: a dozen requests, so there is nothing to
                // gain from concurrency and no reason to burst someone else's server.
                Thread.sleep(props.feed().pauseMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // A category that 404s or briefly fails must not lose the whole run;
                // the feeds overlap, so most of its postings arrive via another.
                failed++;
                log.warn("weworkremotely: {} failed: {}", url, e.toString());
            }
        }

        log.info("weworkremotely: {} feeds read ({} failed), {} unique postings",
                urls.size() - failed, failed, byGuid.size());
        return List.copyOf(byGuid.values());
    }

    private String fetchXml(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", props.userAgent())
                .header("Accept", "application/rss+xml, application/xml, text/xml")
                .timeout(props.requestTimeout())
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private List<FetchedPosting> parseFeed(String xml) throws Exception {
        Document document = secureParser().parse(new InputSource(new StringReader(xml)));
        NodeList items = document.getElementsByTagName("item");

        List<FetchedPosting> out = new ArrayList<>(items.getLength());
        for (int i = 0; i < items.getLength(); i++) {
            FetchedPosting posting = toPosting((Element) items.item(i));
            if (posting != null) {
                out.add(posting);
            }
        }
        return out;
    }

    /**
     * An XML parser with external entity resolution switched off.
     *
     * <p>This is third-party XML fetched over the network. A parser left at its
     * defaults will happily follow a DOCTYPE into the local filesystem or an
     * internal address on request — the XXE class of bug. Disallowing doctypes
     * outright is the blunt fix, and RSS has no legitimate need for them.
     */
    private static DocumentBuilder secureParser() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    private static FetchedPosting toPosting(Element item) {
        String rawTitle = text(item, "title");
        String link = text(item, "link");
        String guid = text(item, "guid");
        if (rawTitle == null || link == null) {
            return null;
        }
        if (guid == null) {
            guid = link;
        }

        // "Employer: Role" is the house format. Splitting on the first colon keeps
        // colons inside the role itself intact.
        String employer;
        String title;
        int split = rawTitle.indexOf(": ");
        if (split > 0) {
            employer = rawTitle.substring(0, split).trim();
            title = rawTitle.substring(split + 2).trim();
        } else {
            employer = "Unknown";
            title = rawTitle;
        }

        String region = text(item, "region");
        String country = text(item, "country");
        String location = region;
        if (country != null && !country.isBlank()) {
            location = (region == null || region.isBlank()) ? country : region + ", " + country;
        }

        return new FetchedPosting(
                guid,
                title,
                link,
                location,
                text(item, "description"),
                rfc1123(text(item, "pubDate")),
                Boolean.TRUE,
                text(item, "category"),
                text(item, "type"),
                serialize(item),
                employer,
                rfc1123(text(item, "expires_at"))
        );
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        Node node = nodes.item(0);
        String value = node.getTextContent();
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static Instant rfc1123(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return ZonedDateTime.parse(raw, RFC_1123).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** Flattens the item's child elements into JSON for the raw layer. */
    private static String serialize(Element item) {
        StringBuilder json = new StringBuilder("{");
        NodeList children = item.getChildNodes();
        boolean first = true;
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String value = node.getTextContent();
            if (value == null) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(quote(node.getNodeName())).append(':').append(quote(value.trim()));
        }
        return json.append('}').toString();
    }

    private static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
