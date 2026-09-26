package com.ecommerce.oms.cart.service;

import com.ecommerce.oms.cart.api.dto.CartDtos.*;
import com.ecommerce.oms.cart.domain.Cart;
import com.ecommerce.oms.cart.domain.CartItem;
import com.ecommerce.oms.cart.repository.CartRepository;
import com.ecommerce.oms.catalog.domain.ProductVariant;
import com.ecommerce.oms.catalog.service.ProductService;
import com.ecommerce.oms.common.error.ApiException;
import com.ecommerce.oms.common.error.ErrorCode;
import com.ecommerce.oms.inventory.service.ReservationCommands.ReservationLine;
import com.ecommerce.oms.pricing.engine.PricingEngine;
import com.ecommerce.oms.pricing.engine.PricingLine;
import com.ecommerce.oms.pricing.engine.PricingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Cart management and price preview.
 *
 * <p>Every method takes the caller's {@code userId} and scopes its query by it. There is no
 * "find cart by id" on the API surface at all, so the class of bug where customer A reads
 * customer B's basket is not merely guarded against — it is unexpressible.
 *
 * <p>The cart holds no prices and no stock. Both are resolved on demand: prices by the pricing
 * engine against the live catalog, stock only at checkout. That keeps an abandoned basket from
 * denying inventory to buyers who are ready to pay, and means a catalog price change is visible
 * the next time the cart is read rather than being frozen when an item was added.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final ProductService productService;
    private final PricingEngine pricingEngine;

    // ------------------------------------------------------------------ reads

    @Transactional
    public CartResponse getCart(Long userId) {
        Cart cart = loadOrCreate(userId);
        return CartResponse.from(cart);
    }

    /**
     * Dry-run pricing: the discount and tax breakdown a customer would get, without placing an
     * order or touching stock.
     *
     * <p>Runs the identical {@link PricingEngine} that checkout runs, which is what makes the
     * preview trustworthy rather than an approximation that drifts from the real total.
     */
    @Transactional
    public CartPreviewResponse preview(Long userId, CartPreviewRequest request) {
        Cart cart = loadOrCreate(userId);
        assertNotEmpty(cart);

        List<PricingLine> lines = toPricingLines(cart);
        PricingResult pricing = pricingEngine.price(
                lines,
                request == null ? null : request.couponCode(),
                request == null ? null : request.destinationZone(),
                userId);

        log.info("Cart preview for userId={}: {} line(s), total={}",
                userId, lines.size(), pricing.grandTotal());
        return new CartPreviewResponse(cart.totalUnits(), cart.distinctSkuCount(), pricing);
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public CartResponse addItem(Long userId, AddCartItemRequest request) {
        Cart cart = loadOrCreate(userId);
        // Validates the variant exists and is purchasable before it can enter the cart, so an
        // archived SKU cannot sit in a basket waiting to fail at checkout.
        ProductVariant variant = productService.loadPurchasableVariant(request.variantId());

        try {
            CartItem item = cart.addOrIncrement(variant, request.quantity());
            cartRepository.save(cart);
            log.info("Cart item added: userId={} sku={} quantity now {}",
                    userId, variant.getSku(), item.getQuantity());
        } catch (IllegalArgumentException rejected) {
            throw ApiException.validation(rejected.getMessage());
        }

        return CartResponse.from(cart);
    }

    @Transactional
    public CartResponse updateQuantity(Long userId, Long variantId, UpdateCartItemRequest request) {
        Cart cart = loadOrCreate(userId);
        cart.findItemFor(variantId).orElseThrow(() -> ApiException.notFound(
                "Cart does not contain variant " + variantId));

        try {
            cart.changeQuantity(variantId, request.quantity());
        } catch (IllegalArgumentException rejected) {
            throw ApiException.validation(rejected.getMessage());
        }

        cartRepository.save(cart);
        log.info("Cart quantity changed: userId={} variantId={} -> {}", userId, variantId, request.quantity());
        return CartResponse.from(cart);
    }

    @Transactional
    public CartResponse removeItem(Long userId, Long variantId) {
        Cart cart = loadOrCreate(userId);
        if (!cart.removeItem(variantId)) {
            throw ApiException.notFound("Cart does not contain variant " + variantId);
        }
        cartRepository.save(cart);
        log.info("Cart item removed: userId={} variantId={}", userId, variantId);
        return CartResponse.from(cart);
    }

    @Transactional
    public void clear(Long userId) {
        Cart cart = loadOrCreate(userId);
        int removed = cart.distinctSkuCount();
        cart.clear();
        cartRepository.save(cart);
        log.info("Cart cleared: userId={} removed {} line(s)", userId, removed);
    }

    // ------------------------------------------------------------------ checkout collaboration

    /**
     * Loads the cart for checkout, with everything the saga needs already fetched.
     *
     * <p>Exposed for {@code CheckoutService} rather than having it reach into
     * {@code CartRepository}: the cart's storage stays the cart module's business, which is the
     * rule that keeps feature packages independently reasonable.
     */
    @Transactional
    public Cart loadForCheckout(Long userId) {
        Cart cart = loadOrCreate(userId);
        assertNotEmpty(cart);
        return cart;
    }

    /** Basket lines as pricing input. Used by both preview and checkout, so they cannot diverge. */
    public List<PricingLine> toPricingLines(Cart cart) {
        return cart.getItems().stream()
                .map(item -> PricingLine.of(item.getVariant(), item.getQuantity()))
                .toList();
    }

    /** Basket lines as reservation input. */
    public List<ReservationLine> toReservationLines(Cart cart) {
        return cart.getItems().stream()
                .map(item -> new ReservationLine(
                        item.getVariant().getId(),
                        item.getVariant().getSku(),
                        item.getQuantity()))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * One cart per user, created on first touch.
     *
     * <p>Lazy creation avoids a registration-time write for an account that may never shop, and
     * makes the cart endpoints safe to call in any order — there is no "create cart" step a client
     * has to remember.
     */
    private Cart loadOrCreate(Long userId) {
        return cartRepository.findByUserIdWithItems(userId)
                .orElseGet(() -> {
                    log.debug("Creating cart for userId={}", userId);
                    return cartRepository.save(Cart.forUser(userId));
                });
    }

    private void assertNotEmpty(Cart cart) {
        if (cart.isEmpty()) {
            throw ApiException.of(ErrorCode.CART_EMPTY, "Your cart is empty");
        }
    }
}
