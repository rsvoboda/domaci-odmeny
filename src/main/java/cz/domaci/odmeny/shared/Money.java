package cz.domaci.odmeny.shared;

/**
 * An exact monetary amount in minor units (haléře for CZK).
 *
 * <p>{@code 50 Kč} is represented as {@code 5000}. Floating-point types are
 * never used for money.
 */
public record Money(long minor) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    public static Money czk(long crowns) {
        return new Money(Math.multiplyExact(crowns, 100));
    }

    public static Money minor(long minor) {
        return new Money(minor);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(minor, other.minor));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(minor, other.minor));
    }

    public Money negate() {
        return new Money(Math.negateExact(minor));
    }

    public boolean isNegative() {
        return minor < 0;
    }

    public boolean isZero() {
        return minor == 0;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(minor, other.minor);
    }
}
