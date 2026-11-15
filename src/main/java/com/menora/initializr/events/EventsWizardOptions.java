package com.menora.initializr.events;

import java.util.List;

/**
 * Per-dependency options of the events wizard. Nulls and blanks fall back to defaults.
 *
 * @param subPackage        where producers and listeners go (default {@code events})
 * @param payloadSubPackage where the payload types go (default {@code events.model})
 * @param groupId           the listeners' consumer group; blank: the application's
 *                          ({@code spring.kafka.consumer.group-id})
 * @param channels          which channels to write code for, and in which direction. Empty: every
 *                          channel, as the application the document describes — a producer where
 *                          it sends, a listener where it receives
 * @param generateTests     a unit test per producer and listener (no broker)
 */
public record EventsWizardOptions(String subPackage, String payloadSubPackage, String groupId,
                                  List<ChannelChoice> channels, boolean generateTests) {

    public static final String DEFAULT_PACKAGE = "events";
    public static final String DEFAULT_PAYLOAD_PACKAGE = "events.model";

    /** One channel: {@code produce} a producer class, {@code consume} a listener class. */
    public record ChannelChoice(String name, boolean produce, boolean consume) {}

    public static final EventsWizardOptions DEFAULTS = new EventsWizardOptions(null, null, null, List.of(), false);

    public EventsWizardOptions {
        channels = channels == null ? List.of() : List.copyOf(channels);
    }

    public String packageOrDefault() {
        return subPackage == null || subPackage.isBlank() ? DEFAULT_PACKAGE : subPackage.trim();
    }

    public String payloadPackageOrDefault() {
        return payloadSubPackage == null || payloadSubPackage.isBlank() ? DEFAULT_PAYLOAD_PACKAGE : payloadSubPackage.trim();
    }
}
