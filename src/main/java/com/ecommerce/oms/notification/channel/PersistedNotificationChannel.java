package com.ecommerce.oms.notification.channel;

import com.ecommerce.oms.notification.domain.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The in-build adapter: "delivery" means the row is durable and the message is logged.
 *
 * <p>No SMTP or SMS, by documented exclusion. What this does provide is the property that matters for
 * demonstrating requirement #3 — the notification is verifiably produced <em>after</em> the checkout
 * response, and can be queried to prove it.
 *
 * <p>Deliberately never throws: a delivery problem is recorded on the row, not escalated into a reason
 * to retry the whole event. A bounced address would otherwise retry until it dead-lettered, hiding real
 * failures behind noise.
 */
@Slf4j
@Component
public class PersistedNotificationChannel implements NotificationChannel {

    @Override
    public String channelName() {
        return "IN_APP";
    }

    @Override
    public boolean send(Notification notification) {
        log.info("NOTIFY userId={} channel={} subject='{}'",
                notification.getUserId(), notification.getChannel(), notification.getSubject());
        log.debug("NOTIFY body: {}", notification.getBody());
        return true;
    }
}
