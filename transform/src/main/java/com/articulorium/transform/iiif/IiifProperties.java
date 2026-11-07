package com.articulorium.transform.iiif;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Settings of the IIIF Presentation export ({@code articulorium.iiif.*}).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "articulorium.iiif")
public class IiifProperties {

    /** Where this service reads the platform's LDP root (a Consul service name when load balanced). */
    private String platformUrl = "http://articulorium-platform/";

    /** Whether {@link #platformUrl} names a Consul service resolved by the load balancer. */
    private boolean loadBalanced = true;

    /**
     * Where clients reach the platform, relative to this service's public base (the gateway serves
     * this service under {@code /transform/} and the platform under {@code /platform/}) or absolute.
     * Manifests link to resources and files under this URL.
     */
    private String platformPublicUrl = "../platform/";

    /** Where clients reach the user interface, relative to this service's public base or absolute. */
    private String uiPublicUrl = "../ui/";

    /** Whether image dimensions are read from the first bytes of each image. */
    private boolean probeImageSize = true;

    /** How many leading bytes of an image are read to find its dimensions. */
    private int probeBytes = 262_144;

    /** Canvas width when an image's dimensions are unknown. */
    private int defaultWidth = 1000;

    /** Canvas height when an image's dimensions are unknown. */
    private int defaultHeight = 1000;

    /**
     * Whether a canvas without image, audio or video is painted with its PDF (a {@code Text} body
     * with format {@code application/pdf}) instead of offering it as a rendering only, so viewers
     * such as Mirador with a document plugin present it.
     */
    private boolean paintDocuments = true;

    /** Largest number of resources read for one export. */
    private int maxResources = 2000;

}
