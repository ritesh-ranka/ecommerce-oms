package com.ecommerce.oms.notification.domain;

/**
 * Delivery mechanisms. Only {@link #IN_APP} has an adapter in this build — SMTP and SMS are documented
 * exclusions, and the port makes adding them a one-class change rather than a refactor.
 */
public enum NotificationChannelType {

    /** Persisted and logged. The default, and what the demo and tests observe. */
    IN_APP,

    EMAIL,

    SMS
}
