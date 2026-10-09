use std::fmt;

const MINIMUM_SKU_LENGTH: usize = 3;
const MAXIMUM_SKU_LENGTH: usize = 32;
const MAXIMUM_LINE_QUANTITY: u32 = 99;
const CART_ID_LENGTH: usize = 36;
const CART_ID_DASH_POSITIONS: [usize; 4] = [8, 13, 18, 23];

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DomainError {
    InvalidSku,
    InvalidQuantity,
    InvalidMoney,
    InvalidCartId,
    AmountOverflow,
    ProductNotFound(Sku),
    CartNotFound(CartId),
    CartClosed,
    EmptyCart,
    OutOfStock(Sku),
}

impl fmt::Display for DomainError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidSku => write!(
                formatter,
                "sku must be {MINIMUM_SKU_LENGTH}-{MAXIMUM_SKU_LENGTH} uppercase letters, digits or dashes"
            ),
            Self::InvalidQuantity => {
                write!(formatter, "quantity must be between 1 and {MAXIMUM_LINE_QUANTITY}")
            }
            Self::InvalidMoney => write!(formatter, "amount must not be negative"),
            Self::InvalidCartId => write!(formatter, "cart id must be a UUID"),
            Self::AmountOverflow => write!(formatter, "order total exceeds the supported range"),
            Self::ProductNotFound(sku) => write!(formatter, "product {sku} does not exist"),
            Self::CartNotFound(cart_id) => write!(formatter, "cart {cart_id} does not exist"),
            Self::CartClosed => write!(formatter, "cart is already checked out"),
            Self::EmptyCart => write!(formatter, "cart has no items"),
            Self::OutOfStock(sku) => write!(formatter, "not enough stock for {sku}"),
        }
    }
}

impl std::error::Error for DomainError {}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct Money(i64);

impl Money {
    pub fn from_cents(cents: i64) -> Result<Self, DomainError> {
        if cents < 0 {
            return Err(DomainError::InvalidMoney);
        }
        Ok(Self(cents))
    }
    pub fn zero() -> Self {
        Self(0)
    }
    pub fn cents(self) -> i64 {
        self.0
    }
    pub fn times(self, quantity: Quantity) -> Result<Self, DomainError> {
        self.0
            .checked_mul(i64::from(quantity.value()))
            .map(Self)
            .ok_or(DomainError::AmountOverflow)
    }
    pub fn plus(self, other: Money) -> Result<Self, DomainError> {
        self.0
            .checked_add(other.0)
            .map(Self)
            .ok_or(DomainError::AmountOverflow)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Sku(String);

impl Sku {
    pub fn parse(raw: &str) -> Result<Self, DomainError> {
        let length_valid = (MINIMUM_SKU_LENGTH..=MAXIMUM_SKU_LENGTH).contains(&raw.len());
        let characters_valid = raw.chars().all(|character| {
            character.is_ascii_uppercase() || character.is_ascii_digit() || character == '-'
        });
        if !length_valid || !characters_valid {
            return Err(DomainError::InvalidSku);
        }
        Ok(Self(raw.to_owned()))
    }
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for Sku {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.0)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Quantity(u32);

impl Quantity {
    pub fn new(value: i64) -> Result<Self, DomainError> {
        u32::try_from(value)
            .ok()
            .filter(|candidate| (1..=MAXIMUM_LINE_QUANTITY).contains(candidate))
            .map(Self)
            .ok_or(DomainError::InvalidQuantity)
    }
    pub fn value(self) -> u32 {
        self.0
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CartId(String);

impl CartId {
    pub fn parse(raw: &str) -> Result<Self, DomainError> {
        let shape_valid = raw.len() == CART_ID_LENGTH
            && raw.char_indices().all(|(position, character)| {
                if CART_ID_DASH_POSITIONS.contains(&position) {
                    character == '-'
                } else {
                    character.is_ascii_hexdigit()
                }
            });
        if !shape_valid {
            return Err(DomainError::InvalidCartId);
        }
        Ok(Self(raw.to_ascii_lowercase()))
    }
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for CartId {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.0)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Product {
    pub sku: Sku,
    pub name: String,
    pub price: Money,
    pub stock: u32,
}

impl Product {
    pub fn ensure_available(&self, quantity: Quantity) -> Result<(), DomainError> {
        if quantity.value() > self.stock {
            return Err(DomainError::OutOfStock(self.sku.clone()));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CartStatus {
    Open,
    CheckedOut,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CartLine {
    pub sku: Sku,
    pub quantity: Quantity,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Cart {
    pub id: CartId,
    pub status: CartStatus,
    pub lines: Vec<CartLine>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OrderLine {
    pub sku: Sku,
    pub quantity: Quantity,
    pub unit_price: Money,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OrderDraft {
    pub cart_id: CartId,
    pub lines: Vec<OrderLine>,
    pub total: Money,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Order {
    pub id: i64,
    pub cart_id: CartId,
    pub lines: Vec<OrderLine>,
    pub total: Money,
}

impl Cart {
    pub fn add(&self, product: &Product, quantity: Quantity) -> Result<CartLine, DomainError> {
        self.ensure_open()?;
        let existing = self
            .lines
            .iter()
            .find(|line| line.sku == product.sku)
            .map_or(0, |line| line.quantity.value());
        let merged = Quantity::new(i64::from(existing) + i64::from(quantity.value()))?;
        product.ensure_available(merged)?;
        Ok(CartLine {
            sku: product.sku.clone(),
            quantity: merged,
        })
    }
    pub fn checkout(&self, products: &[Product]) -> Result<OrderDraft, DomainError> {
        self.ensure_open()?;
        if self.lines.is_empty() {
            return Err(DomainError::EmptyCart);
        }
        let lines = self
            .lines
            .iter()
            .map(|line| Self::price_line(line, products))
            .collect::<Result<Vec<_>, _>>()?;
        let total = lines.iter().try_fold(Money::zero(), |total, line| {
            total.plus(line.unit_price.times(line.quantity)?)
        })?;
        Ok(OrderDraft {
            cart_id: self.id.clone(),
            lines,
            total,
        })
    }
    fn ensure_open(&self) -> Result<(), DomainError> {
        match self.status {
            CartStatus::Open => Ok(()),
            CartStatus::CheckedOut => Err(DomainError::CartClosed),
        }
    }
    fn price_line(line: &CartLine, products: &[Product]) -> Result<OrderLine, DomainError> {
        let product = products
            .iter()
            .find(|product| product.sku == line.sku)
            .ok_or_else(|| DomainError::ProductNotFound(line.sku.clone()))?;
        product.ensure_available(line.quantity)?;
        Ok(OrderLine {
            sku: line.sku.clone(),
            quantity: line.quantity,
            unit_price: product.price,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sku(raw: &str) -> Sku {
        Sku::parse(raw).unwrap()
    }

    fn product(raw_sku: &str, price_cents: i64, stock: u32) -> Product {
        Product {
            sku: sku(raw_sku),
            name: raw_sku.to_owned(),
            price: Money::from_cents(price_cents).unwrap(),
            stock,
        }
    }

    fn cart(status: CartStatus, lines: Vec<CartLine>) -> Cart {
        Cart {
            id: CartId::parse("0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0c").unwrap(),
            status,
            lines,
        }
    }

    fn line(raw_sku: &str, quantity: i64) -> CartLine {
        CartLine {
            sku: sku(raw_sku),
            quantity: Quantity::new(quantity).unwrap(),
        }
    }

    mod sku_parsing {
        use super::*;

        #[test]
        fn accepts_uppercase_digits_and_dashes() {
            assert_eq!(
                Sku::parse("MUG-001").map(|parsed| parsed.to_string()),
                Ok("MUG-001".to_owned())
            );
        }

        #[test]
        fn rejects_lowercase() {
            assert_eq!(Sku::parse("mug-001"), Err(DomainError::InvalidSku));
        }

        #[test]
        fn rejects_too_short() {
            assert_eq!(Sku::parse("AB"), Err(DomainError::InvalidSku));
        }
    }

    mod quantity_bounds {
        use super::*;

        #[test]
        fn rejects_zero() {
            assert_eq!(Quantity::new(0), Err(DomainError::InvalidQuantity));
        }

        #[test]
        fn rejects_above_line_maximum() {
            assert_eq!(Quantity::new(100), Err(DomainError::InvalidQuantity));
        }

        #[test]
        fn accepts_every_value_in_range() {
            assert!((1..=99).all(|value| Quantity::new(value).is_ok()));
        }
    }

    mod cart_id_parsing {
        use super::*;

        #[test]
        fn normalizes_to_lowercase() {
            let parsed = CartId::parse("0B8F6C1E-4A2D-4E1F-9C3B-2D5E7F8A9B0C").unwrap();
            assert_eq!(parsed.as_str(), "0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0c");
        }

        #[test]
        fn rejects_non_uuid() {
            assert_eq!(CartId::parse("42"), Err(DomainError::InvalidCartId));
        }
    }

    mod add_to_cart {
        use super::*;

        #[test]
        fn merges_quantity_with_existing_line() {
            let result = cart(CartStatus::Open, vec![line("MUG-001", 2)])
                .add(&product("MUG-001", 1800, 10), Quantity::new(3).unwrap());
            assert_eq!(result, Ok(line("MUG-001", 5)));
        }

        #[test]
        fn rejects_quantity_beyond_stock() {
            let result = cart(CartStatus::Open, vec![])
                .add(&product("LAMP-1", 12900, 2), Quantity::new(3).unwrap());
            assert_eq!(result, Err(DomainError::OutOfStock(sku("LAMP-1"))));
        }

        #[test]
        fn rejects_merged_quantity_beyond_line_maximum() {
            let result = cart(CartStatus::Open, vec![line("MUG-001", 98)])
                .add(&product("MUG-001", 1800, 1000), Quantity::new(2).unwrap());
            assert_eq!(result, Err(DomainError::InvalidQuantity));
        }

        #[test]
        fn rejects_checked_out_cart() {
            let result = cart(CartStatus::CheckedOut, vec![])
                .add(&product("MUG-001", 1800, 10), Quantity::new(1).unwrap());
            assert_eq!(result, Err(DomainError::CartClosed));
        }
    }

    mod checkout {
        use super::*;

        #[test]
        fn totals_every_line_in_cents() {
            let products = [product("MUG-001", 1800, 10), product("TEE-M", 2900, 10)];
            let draft = cart(CartStatus::Open, vec![line("MUG-001", 2), line("TEE-M", 1)])
                .checkout(&products)
                .unwrap();
            assert_eq!(draft.total.cents(), 6500);
        }

        #[test]
        fn rejects_empty_cart() {
            let result = cart(CartStatus::Open, vec![]).checkout(&[]);
            assert_eq!(result, Err(DomainError::EmptyCart));
        }

        #[test]
        fn rejects_line_that_exceeds_current_stock() {
            let result = cart(CartStatus::Open, vec![line("LAMP-1", 2)])
                .checkout(&[product("LAMP-1", 12900, 1)]);
            assert_eq!(result, Err(DomainError::OutOfStock(sku("LAMP-1"))));
        }

        #[test]
        fn rejects_checked_out_cart() {
            let result = cart(CartStatus::CheckedOut, vec![line("MUG-001", 1)])
                .checkout(&[product("MUG-001", 1800, 10)]);
            assert_eq!(result, Err(DomainError::CartClosed));
        }

        #[test]
        fn rejects_a_line_whose_product_is_missing() {
            let result = cart(CartStatus::Open, vec![line("MUG-001", 1)]).checkout(&[]);
            assert_eq!(result, Err(DomainError::ProductNotFound(sku("MUG-001"))));
        }

        #[test]
        fn rejects_a_total_that_overflows() {
            let products = [product("MUG-001", i64::MAX, 10)];
            let result = cart(CartStatus::Open, vec![line("MUG-001", 2)]).checkout(&products);
            assert_eq!(result, Err(DomainError::AmountOverflow));
        }
    }

    #[test]
    fn money_rejects_a_negative_amount_and_an_overflowing_sum() {
        assert_eq!(Money::from_cents(-1), Err(DomainError::InvalidMoney));
        let max = Money::from_cents(i64::MAX).unwrap();
        assert_eq!(
            max.plus(Money::from_cents(1).unwrap()),
            Err(DomainError::AmountOverflow)
        );
        assert_eq!(
            max.times(Quantity::new(2).unwrap()),
            Err(DomainError::AmountOverflow)
        );
    }

    #[test]
    fn sku_accepts_the_longest_code_and_exposes_it() {
        let longest = "A".repeat(32);
        let parsed = Sku::parse(&longest).unwrap();
        assert_eq!(parsed.as_str(), longest);
        assert_eq!(Sku::parse(&"A".repeat(33)), Err(DomainError::InvalidSku));
    }

    #[test]
    fn cart_id_rejects_a_dash_in_the_wrong_place() {
        assert_eq!(
            CartId::parse("0b8f6c1e4a2d-4e1f-9c3b-2d5e7f8a9b0c"),
            Err(DomainError::InvalidCartId)
        );
    }

    #[test]
    fn every_domain_error_names_the_problem() {
        let sku = sku("MUG-001");
        let cart_id = CartId::parse("0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0c").unwrap();
        let messages = [
            DomainError::InvalidSku.to_string(),
            DomainError::InvalidQuantity.to_string(),
            DomainError::InvalidMoney.to_string(),
            DomainError::InvalidCartId.to_string(),
            DomainError::AmountOverflow.to_string(),
            DomainError::ProductNotFound(sku.clone()).to_string(),
            DomainError::CartNotFound(cart_id.clone()).to_string(),
            DomainError::CartClosed.to_string(),
            DomainError::EmptyCart.to_string(),
            DomainError::OutOfStock(sku).to_string(),
            cart_id.to_string(),
        ];
        assert!(messages.iter().all(|message| !message.is_empty()));
        let as_error: &dyn std::error::Error = &DomainError::InvalidSku;
        assert_eq!(as_error.to_string(), DomainError::InvalidSku.to_string());
    }
}
