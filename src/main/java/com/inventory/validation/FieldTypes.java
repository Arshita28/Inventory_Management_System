package com.inventory.validation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Explicit parsing at input boundaries; no rounding, truncation or exponent notation. */
public final class FieldTypes {
    private FieldTypes() { }
    public static String label(String key) {
        if(key.matches("qty[0-9]+|quantity[0-9]+")) return "Quantity";
        String words=key.replaceAll("([a-z])([A-Z])", "$1 $2");
        return Character.toUpperCase(words.charAt(0))+words.substring(1);
    }
    public static int integer(String value,String label) {
        if(value==null || !value.strip().matches("[0-9]+")) throw new IllegalArgumentException(label+" must be a whole number; letters and decimals are not allowed.");
        try { return Integer.parseInt(value.strip()); }
        catch(NumberFormatException exception) {throw new IllegalArgumentException(label+" must be between 0 and 2,147,483,647.");}
    }
    public static BigDecimal decimal(String value,String label) {
        if(value==null || !value.strip().matches("[0-9]+(?:\\.[0-9]{0,2})?")) throw new IllegalArgumentException(label+" must be a nonnegative amount with at most two decimal places.");
        BigDecimal number=new BigDecimal(value.strip());
        if(number.compareTo(new BigDecimal("99999999999999999.99"))>0) throw new IllegalArgumentException(label+" exceeds the supported amount.");
        return number.setScale(2,RoundingMode.UNNECESSARY);
    }
}
