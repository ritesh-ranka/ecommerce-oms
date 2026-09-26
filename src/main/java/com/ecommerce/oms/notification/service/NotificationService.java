package com.ecommerce.oms.notification.service;

import com.ecommerce.oms.common.web.PageResponse;
import com.ecommerce.oms.notification.channel.NotificationChannel;
import com.ecommerce.oms.notification.domain.Notification;
import com.ecommerce.oms.notification.domain.NotificationChannelType;
import com.ecommerce.oms.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Composes customer messages and hands them to a {@link NotificationChannel}.
 *
 * <p>Composition and delivery are separated so message wording lives in one place and transport lives
 * behind a port. The row is persisted before delivery is attempted, so a failed send leaves a record of
 * what was owed rather than vanishing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final List<NotificationChannel> channels;

    private Map<String, NotificationChannel> channelsByName() {
        return channels.stream().collect(Collectors.toMap(
                NotificationChannel::channelName, Function.identity(), (a, b) -> a));
    }

    /**
     * Persists a notification and attempts delivery.
     *
     * <p>Runs inside the calling outbox handler's transaction, so the row and the handler's completion
     * marker commit together. If delivery fails the row is kept as {@code FAILED} — visible to support —
     * rather than throwing, because a bounced message is not a reason to retry the whole event.
     */
    @Transactional
    public Notification notify(Long userId, NotificationChannelType channelType,
                               String subject, String body) {
        Notification notification = notificationRepository.save(
                Notification.pending(userId, channelType, subject, body));

        NotificationChannel channel = channelsByName().getOrDefault(
                channelType.name(), channelsByName().get(NotificationChannelType.IN_APP.name()));

        boolean delivered = channel != null && channel.send(notification);
        if (delivered) {
            notification.markSent();
        } else {
            notification.markFailed();
            log.warn("Notification {} could not be delivered to userId={}", notification.getId(), userId);
        }

        return notificationRepository.save(notification);
    }

    /** Default channel for order lifecycle messages. */
    @Transactional
    public Notification notifyInApp(Long userId, String subject, String body) {
        return notify(userId, NotificationChannelType.IN_APP, subject, body);
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationView> forUser(Long userId, Pageable pageable) {
        return PageResponse.from(
                notificationRepository.findByUserIdOrderByIdDesc(userId, pageable),
                NotificationView::from);
    }

    /** Read model for the customer-facing notification list. */
    public record NotificationView(
            Long id,
            NotificationChannelType channel,
            String subject,
            String body,
            Notification.Status status,
            Instant sentAt,
            Instant createdAt
    ) {
        static NotificationView from(Notification notification) {
            return new NotificationView(notification.getId(), notification.getChannel(),
                    notification.getSubject(), notification.getBody(), notification.getStatus(),
                    notification.getSentAt(), notification.getCreatedAt());
        }
    }
}
