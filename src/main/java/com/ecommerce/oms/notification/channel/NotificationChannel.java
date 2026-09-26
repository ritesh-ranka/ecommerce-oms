package com.ecommerce.oms.notification.channel;

import com.ecommerce.oms.notification.domain.Notification;

/**
 * Port to a delivery mechanism — email, SMS, push (Port and Adapter).
 *
 * <p>The interface exists so the notification service composes the message and something else decides
 * how it travels. Adding SMTP or an SMS provider is one new adapter; nothing that produces
 * notifications changes.
 *
 * <p>Implementations must not throw for an ordinary delivery failure. A bounced email is not a reason to
 * roll back an order, so failures are recorded on the {@link Notification} row instead. That is also what
 * keeps the outbox handler's retry semantics honest: a genuine exception means "retry me", and a bounced
 * address would retry forever.
 */
public interface NotificationChannel {

    String channelName();

    /**
     * Attempts delivery and records the outcome on the notification.
     *
     * @return true if delivered
     */
    boolean send(Notification notification);
}
