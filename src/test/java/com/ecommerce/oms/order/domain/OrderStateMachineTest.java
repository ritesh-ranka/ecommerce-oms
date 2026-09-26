package com.ecommerce.oms.order.domain;

import com.ecommerce.oms.common.error.ApiException;
import com.ecommerce.oms.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exhaustive test of the order lifecycle.
 *
 * <p>The state machine is pure and dependency-free, so all 81 {@code status x status} pairs can be
 * asserted against an explicit expectation for a fraction of the cost of one integration test. That
 * is the return on making the rule a table: complete coverage of a high-risk invariant, with no
 * database, no Spring context, and no fixtures.
 */
class OrderStateMachineTest {

    /**
     * The transition table restated independently of the implementation.
     *
     * <p>Duplicating it is the point. If someone widens a transition in production code, this map does
     * not change with it and the matrix test fails — which is exactly the review conversation that
     * should happen before, say, a shipped order becomes cancellable.
     */
    private static final Map<OrderStatus, Set<OrderStatus>> EXPECTED = Map.of(
            OrderStatus.AWAITING_PAYMENT, Set.of(
                    OrderStatus.CONFIRMED, OrderStatus.PAYMENT_FAILED, OrderStatus.CANCELLED),
            OrderStatus.CONFIRMED, Set.of(OrderStatus.PACKED, OrderStatus.CANCELLED),
            OrderStatus.PACKED, Set.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED),
            OrderStatus.SHIPPED, Set.of(OrderStatus.DELIVERED),
            OrderStatus.DELIVERED, Set.of(OrderStatus.RETURN_REQUESTED),
            OrderStatus.RETURN_REQUESTED, Set.of(OrderStatus.RETURNED, OrderStatus.DELIVERED),
            OrderStatus.RETURNED, Set.of(),
            OrderStatus.CANCELLED, Set.of(),
            OrderStatus.PAYMENT_FAILED, Set.of());

    @Test
    @DisplayName("every one of the 81 status pairs matches the expected table")
    void exhaustiveMatrix() {
        Set<String> unexpectedlyAllowed = new HashSet<>();
        Set<String> unexpectedlyBlocked = new HashSet<>();

        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                boolean actual = OrderStateMachine.canTransition(from, to);
                boolean expected = EXPECTED.getOrDefault(from, Set.of()).contains(to);

                if (actual && !expected) {
                    unexpectedlyAllowed.add(from + " -> " + to);
                } else if (!actual && expected) {
                    unexpectedlyBlocked.add(from + " -> " + to);
                }
            }
        }

        assertThat(unexpectedlyAllowed).as("transitions allowed that should not be").isEmpty();
        assertThat(unexpectedlyBlocked).as("transitions blocked that should be allowed").isEmpty();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("no status can transition to itself")
    void noSelfTransitions(OrderStatus status) {
        assertThat(OrderStateMachine.canTransition(status, status)).isFalse();
    }

    @Test
    @DisplayName("the three terminal states accept nothing further")
    void terminalStatesAreClosed() {
        for (OrderStatus terminal : Set.of(
                OrderStatus.RETURNED, OrderStatus.CANCELLED, OrderStatus.PAYMENT_FAILED)) {
            assertThat(OrderStateMachine.isTerminal(terminal)).isTrue();
            assertThat(OrderStateMachine.allowedFrom(terminal)).isEmpty();
            for (OrderStatus target : OrderStatus.values()) {
                assertThat(OrderStateMachine.canTransition(terminal, target))
                        .as("%s -> %s must be blocked", terminal, target)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("a dispatched order cannot be cancelled — the returns flow is the only route back")
    void cannotCancelAfterDispatch() {
        assertThat(OrderStateMachine.canTransition(OrderStatus.SHIPPED, OrderStatus.CANCELLED)).isFalse();
        assertThat(OrderStateMachine.canTransition(OrderStatus.DELIVERED, OrderStatus.CANCELLED)).isFalse();

        // ...but it is allowed right up to dispatch, because until then it is a refund and a restock.
        assertThat(OrderStateMachine.canTransition(OrderStatus.CONFIRMED, OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStateMachine.canTransition(OrderStatus.PACKED, OrderStatus.CANCELLED)).isTrue();
    }

    @Test
    @DisplayName("fulfillment cannot skip a step")
    void fulfillmentCannotSkipSteps() {
        assertThat(OrderStateMachine.canTransition(OrderStatus.CONFIRMED, OrderStatus.SHIPPED)).isFalse();
        assertThat(OrderStateMachine.canTransition(OrderStatus.CONFIRMED, OrderStatus.DELIVERED)).isFalse();
        assertThat(OrderStateMachine.canTransition(OrderStatus.PACKED, OrderStatus.DELIVERED)).isFalse();
    }

    @Test
    @DisplayName("a rejected return goes back to DELIVERED so the customer may ask again")
    void rejectedReturnIsNotTerminal() {
        assertThat(OrderStateMachine.canTransition(
                OrderStatus.RETURN_REQUESTED, OrderStatus.DELIVERED)).isTrue();
        assertThat(OrderStateMachine.canTransition(
                OrderStatus.RETURN_REQUESTED, OrderStatus.RETURNED)).isTrue();
    }

    @Test
    @DisplayName("a return cannot be requested on goods that were never delivered")
    void returnRequiresDelivery() {
        for (OrderStatus from : OrderStatus.values()) {
            if (from != OrderStatus.DELIVERED) {
                assertThat(OrderStateMachine.canTransition(from, OrderStatus.RETURN_REQUESTED))
                        .as("%s -> RETURN_REQUESTED must be blocked", from)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("an illegal move raises 409 and names what IS permitted")
    void illegalTransitionExplainsItself() {
        assertThatThrownBy(() -> OrderStateMachine.assertCanTransition(
                OrderStatus.SHIPPED, OrderStatus.CANCELLED))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                        .isEqualTo(ErrorCode.ILLEGAL_TRANSITION))
                .hasMessageContaining("SHIPPED")
                .hasMessageContaining("CANCELLED")
                .hasMessageContaining("DELIVERED");   // the allowed alternative
    }

    @Test
    @DisplayName("an illegal move out of a terminal state says so rather than listing an empty set")
    void terminalRejectionIsExplicit() {
        assertThatThrownBy(() -> OrderStateMachine.assertCanTransition(
                OrderStatus.CANCELLED, OrderStatus.CONFIRMED))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("terminal state");
    }

    @Test
    @DisplayName("the happy path is walkable end to end")
    void happyPathIsWalkable() {
        OrderStatus[] path = {
                OrderStatus.AWAITING_PAYMENT, OrderStatus.CONFIRMED, OrderStatus.PACKED,
                OrderStatus.SHIPPED, OrderStatus.DELIVERED, OrderStatus.RETURN_REQUESTED,
                OrderStatus.RETURNED
        };
        for (int i = 0; i < path.length - 1; i++) {
            assertThat(OrderStateMachine.canTransition(path[i], path[i + 1]))
                    .as("%s -> %s must be allowed", path[i], path[i + 1])
                    .isTrue();
        }
    }

    @Test
    @DisplayName("status helper predicates agree with the transition table")
    void helperPredicatesAgree() {
        assertThat(OrderStatus.SHIPPED.isDispatched()).isTrue();
        assertThat(OrderStatus.PACKED.isDispatched()).isFalse();
        assertThat(OrderStatus.CONFIRMED.holdsCommittedStock()).isTrue();
        assertThat(OrderStatus.AWAITING_PAYMENT.holdsCommittedStock()).isFalse();

        for (OrderStatus status : OrderStatus.values()) {
            assertThat(status.isTerminal())
                    .as("%s: enum terminality must match the transition table", status)
                    .isEqualTo(OrderStateMachine.isTerminal(status));
        }
    }
}
