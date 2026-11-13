package com.articulorium.discovery.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Settings of the discovery layer ({@code articulorium.discovery.*}): where it finds the search
 * index (Solr or Elasticsearch), the platform and the IIIF exports, and everything a deployment customizes without touching the user
 * interface's code: the site's identity, its theme (colors, typography, shape, extra styles), page
 * layouts, the viewer, the search (fields and their precedence, facets, filters, sort orders) and
 * the search engine directives.
 *
 * <p>Every value can be set in {@code application.yml}, environment variables or Consul's
 * key/value store ({@code config/discovery/articulorium/discovery/...}); see
 * {@code discovery/README.md}. Values are checked at start-up by {@link DiscoverySettingsValidator}.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "articulorium.discovery")
public class DiscoveryProperties {

    /**
     * Public address of the discovery layer, ending with {@code /} (for example
     * {@code https://collections.example.org/discover/}). Canonical URLs, alternate language links,
     * Open Graph URLs and sitemaps are built from it, so it must be the address search engines and
     * people use.
     */
    private String publicUrl = "http://localhost:8000/discover/";

    /** Where this service reads the platform's LDP root (a Consul service name when load balanced). */
    private String platformUrl = "http://articulorium-platform/";

    /** Where this service reads the transform service (IIIF exports). */
    private String transformUrl = "http://articulorium-transform/";

    /** Whether {@link #platformUrl} and {@link #transformUrl} name Consul services. */
    private boolean loadBalanced = true;

    /**
     * Where clients reach the platform, relative to {@link #publicUrl} or absolute. Solr documents
     * are identified by these public IRIs.
     */
    private String platformPublicUrl = "../platform/";

    /** Where clients reach the transform service, relative to {@link #publicUrl} or absolute. */
    private String transformPublicUrl = "../transform/";

    /**
     * Other bases under which the platform mints the IRIs of indexed resources. The platform
     * identifies a resource by the address of the request that wrote it: resources written through
     * the gateway carry public IRIs ({@link #platformPublicUrl}), resources written by services
     * reaching the platform directly (the topology and harvest services) carry IRIs under
     * {@link #platformUrl}, which is always recognized. List any further addresses here.
     */
    private List<String> platformIriBases = new ArrayList<>();

    /** Which search index is read. */
    private Index index = new Index();

    /** Solr. */
    private Solr solr = new Solr();

    /** Elasticsearch. */
    private Elasticsearch elasticsearch = new Elasticsearch();

    /** Who may see what. */
    private Access access = new Access();

    /** IIIF summaries of resources. */
    private Iiif iiif = new Iiif();

    /** The site's identity. */
    private Site site = new Site();

    /** Colors, typography, shape and extra styles. */
    private Theme theme = new Theme();

    /** Page layouts and presentation options. */
    private Layout layout = new Layout();

    /** The Mirador viewer. */
    private Viewer viewer = new Viewer();

    /** Search fields and precedence, facets, filters, sort orders. */
    private Search search = new Search();

    /** Directives and metadata for search engines and social sharing. */
    private Seo seo = new Seo();

    /** Search index backends. */
    public enum Backend {

        /** Apache Solr ({@link Solr}). */
        SOLR,

        /** Elasticsearch ({@link Elasticsearch}). */
        ELASTICSEARCH

    }

    /** The search index. */
    @Getter
    @Setter
    public static class Index {

        /**
         * The search index that is read: the one the index service writes
         * ({@code articulorium.index.backend}). Solr unless set.
         */
        private Backend backend = Backend.SOLR;

    }

    /** Elasticsearch connection, used when {@link Index#getBackend()} is {@link Backend#ELASTICSEARCH}. */
    @Getter
    @Setter
    public static class Elasticsearch {

        /** Base URL of an Elasticsearch node or of a load balancer in front of the cluster. */
        private String baseUrl = "http://elasticsearch:9200";

        /** Index (or alias) the index service keeps in sync. */
        private String index = "articulorium";

        /**
         * API key ({@code Authorization: ApiKey <key>}): the Base64 "encoded" value Elasticsearch
         * returns when the key is created; reading the index is all it needs to allow. Used
         * instead of {@link #username} when set.
         */
        private String apiKey;

        /** User of HTTP basic authentication; no credentials are sent when unset. */
        private String username;

        /** Password of {@link #username}. */
        private String password;

    }

    /** Solr connection. */
    @Getter
    @Setter
    public static class Solr {

        /** Base URL of a Solr node, including the {@code /solr} context. */
        private String baseUrl = "http://solr:8983/solr";

        /** Collection the index service keeps in sync. */
        private String collection = "articulorium";

        /** Request handler queried for searches. */
        private String handler = "select";

    }

    /** Access checks against the platform's Web Access Control. */
    @Getter
    @Setter
    public static class Access {

        /**
         * Whether every resource is checked against the platform before it is shown. Turn off only
         * when the Solr collection holds nothing but public resources.
         */
        private boolean verify = true;

        /** How long the answer for anonymous visitors is remembered, in seconds (0: never). */
        private int anonymousCacheSeconds = 60;

        /** Largest number of remembered answers. */
        private int cacheSize = 10_000;

        /** Concurrent access checks of one page of results. */
        private int parallelism = 8;

    }

    /** Summaries of the IIIF exports shown on resource pages. */
    @Getter
    @Setter
    public static class Iiif {

        /** Whether resource pages read their IIIF export (thumbnail, kinds of media). */
        private boolean enabled = true;

        /** How long the summary for anonymous visitors is remembered, in seconds (0: never). */
        private int cacheSeconds = 300;

        /** Largest number of remembered summaries. */
        private int cacheSize = 2_000;

    }

    /**
     * Text in several languages: a default and translations by language tag.
     *
     * <p>{@code key} names a text of the user interface's translations instead (for example
     * {@code facets.type}), translated like every other text of the interface.</p>
     */
    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class Text {

        /** Text in the default locale, or when nothing else matches. */
        private String value;

        /** Translations by BCP 47 language tag. */
        private Map<String, String> translations = new LinkedHashMap<>();

        /** A key of the user interface's translations, used before {@link #value}. */
        private String key;

        /**
         * Creates text with a translation key.
         *
         * @param key the key
         * @return the text
         */
        public static Text ofKey(final String key) {
            final Text text = new Text();
            text.setKey(key);
            return text;
        }

        /**
         * Creates text with a value.
         *
         * @param value the value
         * @return the text
         */
        public static Text of(final String value) {
            final Text text = new Text();
            text.setValue(value);
            return text;
        }

    }

    /** The site's identity. */
    @Getter
    @Setter
    public static class Site {

        /** Name of the site: page titles, the header, the publisher of structured data. */
        private Text name = Text.of("Articulorium");

        /** Short description: the home page's meta description and introduction. */
        private Text description = Text.ofKey("site.description");

        /** Optional tagline shown below the name on the home page. */
        private Text tagline;

        /** Logo image (absolute, or relative to the public URL); empty: the name only. */
        private String logoUrl;

        /** Favicon; empty: the bundled one. */
        private String faviconUrl;

        /** Image shared on social networks when a page has none of its own. */
        private String defaultImageUrl;

        /** Organization publishing the site (structured data); empty: the site's name. */
        private String organizationName;

        /** Web site of the publishing organization. */
        private String organizationUrl;

        /** The site's account on X/Twitter ({@code @handle}) for Twitter cards. */
        private String twitterSite;

    }

    /** Colors, typography, shape and extra styles. */
    @Getter
    @Setter
    public static class Theme {

        /** {@code light}, {@code dark} or {@code system} (follows the visitor's preference). */
        private String colorScheme = "light";

        /**
         * Colors of the Material 3 system tokens, by token name (for example {@code primary},
         * {@code on-primary}, {@code surface}); see {@code DiscoverySettingsValidator#COLOR_TOKENS}.
         * Any CSS color value. Unset tokens keep the built-in theme's color.
         */
        private Map<String, String> colors = new LinkedHashMap<>();

        /** Colors used instead of {@link #colors} in dark mode. */
        private Map<String, String> darkColors = new LinkedHashMap<>();

        /** Typography. */
        private Typography typography = new Typography();

        /** Corner radius of cards, fields and buttons, in pixels; empty: the built-in shape. */
        private Integer cornerRadius;

        /** Material density scale: 0 (default) to -4 (most compact). */
        private int density;

        /** Largest width of page content (a CSS length). */
        private String contentWidth = "1200px";

        /**
         * A style sheet loaded after the built-in styles (absolute, or relative to the public URL):
         * the extension point for deployments that need more than tokens.
         */
        private String stylesheetUrl;

        /** CSS added after the built-in styles (no HTML: {@code <} is not allowed). */
        private String customCss;

    }

    /** Typography. */
    @Getter
    @Setter
    public static class Typography {

        /** Font family of headings ({@code --mat-sys-*} brand font). */
        private String brandFamily;

        /** Font family of body text. */
        private String plainFamily;

        /** Style sheet declaring the fonts (for example a web font service), loaded first. */
        private String stylesheetUrl;

        /** Base font size (a CSS length). */
        private String baseSize = "16px";

    }

    /** Page layouts and presentation options. */
    @Getter
    @Setter
    public static class Layout {

        /** Header. */
        private Header header = new Header();

        /** Footer. */
        private Footer footer = new Footer();

        /** Home page. */
        private Home home = new Home();

        /** Search page. */
        private Results results = new Results();

        /** Resource pages. */
        private Item item = new Item();

    }

    /** A link of the header or the footer. */
    @Getter
    @Setter
    public static class Link {

        /** Text of the link. */
        private Text label;

        /** Target: absolute, relative to the public URL, or a route ({@code search}). */
        private String url;

    }

    /** Header. */
    @Getter
    @Setter
    public static class Header {

        /** Whether the header has a search field (the home page has its own). */
        private boolean search = true;

        /** Links after the built-in navigation (home, search). */
        private List<Link> links = new ArrayList<>();

    }

    /** Footer. */
    @Getter
    @Setter
    public static class Footer {

        /** Text of the footer (for example a copyright or an address). */
        private Text text;

        /** Links of the footer. */
        private List<Link> links = new ArrayList<>();

    }

    /** Sections of the home page. */
    public enum HomeSection {

        /** The site's name, tagline and description. */
        HERO,

        /** A search field. */
        SEARCH,

        /** The top-level communities and collections. */
        COLLECTIONS,

        /** Recently changed resources. */
        RECENT

    }

    /** Home page. */
    @Getter
    @Setter
    public static class Home {

        /** Sections, in order. */
        private List<HomeSection> sections = new ArrayList<>(List.of(HomeSection.HERO, HomeSection.SEARCH,
            HomeSection.COLLECTIONS, HomeSection.RECENT));

        /**
         * Container whose communities and collections the collections section lists: the
         * platform's community root by default, so the top-level communities.
         */
        private String collectionsPath = "communities";

        /** Number of communities and collections listed. */
        private int collectionsLimit = 12;

        /** Number of recent resources listed. */
        private int recentLimit = 6;

    }

    /** How results are listed. */
    public enum ResultView {

        /** One result per row with its description. */
        LIST,

        /** Cards in a grid. */
        GRID

    }

    /** Side of the facets. */
    public enum FacetPosition {

        /** Before the results (left in left-to-right languages). */
        START,

        /** After the results. */
        END

    }

    /** Search page. */
    @Getter
    @Setter
    public static class Results {

        /** How results are listed. */
        private ResultView view = ResultView.LIST;

        /** Side of the facets. */
        private FacetPosition facetPosition = FacetPosition.START;

        /** Whether results show a thumbnail when Solr has one ({@link Search#getThumbnailField()}). */
        private boolean thumbnails = true;

        /** Results per page. */
        private int pageSize = 20;

        /** Page sizes offered. */
        private List<Integer> pageSizes = new ArrayList<>(List.of(10, 20, 50));

        /** Length of descriptions in results, in characters. */
        private int descriptionLength = 280;

    }

    /** Position of the viewer on resource pages. */
    public enum ViewerPosition {

        /** Before the description and metadata. */
        TOP,

        /** After the description and metadata. */
        BOTTOM,

        /** Beside the description and metadata on wide screens. */
        SIDE

    }

    /** Resource pages. */
    @Getter
    @Setter
    public static class Item {

        /** Position of the viewer. */
        private ViewerPosition viewerPosition = ViewerPosition.TOP;

        /**
         * Metadata fields (Solr fields) listed on resource pages, in order, with their labels;
         * fields without a value are left out.
         */
        private List<Field> fields = new ArrayList<>();

        /** Number of members listed on community and collection pages. */
        private int membersLimit = 24;

        /** Whether resource pages link to the resource in the repository's user interface. */
        private boolean repositoryLink;

        /** The repository's user interface, relative to the public URL or absolute. */
        private String repositoryUiUrl = "../ui/";

    }

    /** A metadata field of resource pages. */
    @Getter
    @Setter
    public static class Field {

        /** Solr field. */
        private String field;

        /** Label; default: the translation key {@code fields.<field>}. */
        private Text label;

        /** Whether values are links (IRIs). */
        private boolean link;

    }

    /** The Mirador viewer. */
    @Getter
    @Setter
    public static class Viewer {

        /** Whether resource pages present their IIIF export in Mirador. */
        private boolean enabled = true;

        /** Height of the viewer (a CSS length). */
        private String height = "70vh";

        /** Whether the side bar (information, index, annotations) starts open. */
        private boolean sideBarOpen;

        /** {@code far-bottom}, {@code far-right} or {@code off}. */
        private String thumbnailNavigation = "far-bottom";

        /** Whether the full screen button is offered. */
        private boolean allowFullscreen = true;

        /** Whether PDFs painted on canvases are presented by the PDF plugin. */
        private boolean pdf = true;

    }

    /** A search field and its weight. */
    @Getter
    @Setter
    public static class SearchField {

        /** Solr field. */
        private String field;

        /** Weight: hits in fields with a higher boost rank first. */
        private double boost = 1;

        /**
         * Whether the field also has language-specific variants ({@code <field>_txt_<lang>}, written
         * by the transform service for language-tagged literals); the variant of the visitor's
         * language is searched with the same boost.
         */
        private boolean localized;

    }

    /** Kinds of facets. */
    public enum FacetType {

        /** The most frequent values of a field. */
        TERMS,

        /** Ranges of years of a date field. */
        YEAR_RANGE

    }

    /** Order of facet values. */
    public enum FacetSort {

        /** Most frequent first. */
        COUNT,

        /** Alphabetical. */
        INDEX

    }

    /** A facet. */
    @Getter
    @Setter
    public static class Facet {

        /** Identifier of the facet in URLs ({@code f=<id>:<value>}). */
        private String id;

        /**
         * Solr field: a string field for {@link FacetType#TERMS} (with the {@code _default} config
         * set, {@code <field>_str} copies of text fields), a date field for
         * {@link FacetType#YEAR_RANGE}.
         */
        private String field;

        /** Label; default: the translation key {@code facets.<id>}. */
        private Text label;

        /** Kind of facet. */
        private FacetType type = FacetType.TERMS;

        /** Values listed. */
        private int limit = 10;

        /** Order of values. */
        private FacetSort sort = FacetSort.COUNT;

        /** Whether the facet starts expanded. */
        private boolean expanded = true;

        /** Labels of values (for example of type IRIs), by value. */
        private Map<String, Text> values = new LinkedHashMap<>();

        /** First year of {@link FacetType#YEAR_RANGE}. */
        private int start = 1800;

        /** Year after the last range of {@link FacetType#YEAR_RANGE}; 0: next year. */
        private int end;

        /** Years per range of {@link FacetType#YEAR_RANGE}. */
        private int gap = 10;

    }

    /** A filter visitors switch on and off. */
    @Getter
    @Setter
    public static class Filter {

        /** Identifier of the filter in URLs ({@code filter=<id>}). */
        private String id;

        /** Label; default: the translation key {@code filters.<id>}. */
        private Text label;

        /** Filter query (Lucene query syntax) applied while the filter is on. */
        private String query;

    }

    /** A sort order. */
    @Getter
    @Setter
    public static class Sort {

        /** Identifier of the order in URLs ({@code sort=<id>}). */
        private String id;

        /** Label; default: the translation key {@code sorts.<id>}. */
        private Text label;

        /** Sort ({@code score desc}, {@code lastModified desc}); empty sorts by relevance. */
        private String value;

    }

    /** Search. */
    @Getter
    @Setter
    public static class Search {

        /** Fields searched and their precedence; see {@code application.yml} for the defaults. */
        private List<SearchField> fields = new ArrayList<>();

        /** Fields where whole phrases rank higher ({@code pf}), with their boosts. */
        private List<SearchField> phraseFields = new ArrayList<>();

        /** Queries whose hits rank higher ({@code bq}), for example a resource type with a boost. */
        private List<String> boostQueries = new ArrayList<>();

        /** How many search terms must match ({@code mm}). */
        private String minimumMatch = "2<-1 5<80%";

        /**
         * Filter queries applied to every search: what the discovery layer shows at all. The
         * defaults select communities, collections and PCDM objects that are not nested in another
         * object (pages of a book, which the topology service builds in {@code members}
         * containers).
         */
        private List<String> baseFilters = new ArrayList<>();

        /** Facets, in order. */
        private List<Facet> facets = new ArrayList<>();

        /** Filters visitors switch on and off. */
        private List<Filter> filters = new ArrayList<>();

        /** Sort orders offered; the first is the default for searches with terms. */
        private List<Sort> sorts = new ArrayList<>();

        /** Sort of searches without terms (browsing); empty: the first sort. */
        private String browseSort = "lastModified desc";

        /** Field of titles. */
        private String titleField = "title";

        /** Field of descriptions. */
        private String descriptionField = "description";

        /** Field of dates shown in results. */
        private String dateField = "date";

        /** Field holding a thumbnail URL; empty: no thumbnails in results. */
        private String thumbnailField = "thumbnail";

        /** Exact-value field of resource types (kinds of resources, type facets). */
        private String typeField = "type_str";


        /** Field of the date of the last modification (recent resources, sitemaps). */
        private String modifiedField = "lastModified";

        /** Largest page size a request may ask for. */
        private int maxRows = 100;

    }

    /** Search engines and social sharing. */
    @Getter
    @Setter
    public static class Seo {

        /** Robots directives of pages meant to be indexed. */
        private String robots = "index, follow";

        /** Robots directives of search result pages (endless combinations of parameters). */
        private String searchRobots = "noindex, follow";

        /** Whether nothing is indexed at all (test and staging deployments). */
        private boolean noindex;

        /** Twitter card type. */
        private String twitterCard = "summary_large_image";

        /** Whether sitemaps are offered. */
        private boolean sitemap = true;

        /** Largest number of resources in the sitemap. */
        private int sitemapLimit = 5_000;

    }

}
