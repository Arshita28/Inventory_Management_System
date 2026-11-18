package com.inventory.validation;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FieldTypesTest {
    @Test void integersPreserveWholeValuesAndRejectFractionsNotationAndOverflow() {
        assertEquals(0,FieldTypes.integer("0","Quantity"));
        assertEquals(2147483647,FieldTypes.integer("2147483647","Quantity"));
        for(String value:List.of("1.0","1.5","1e3","+2","-1","words","2147483648"))
            assertThrows(IllegalArgumentException.class,()->FieldTypes.integer(value,"Quantity"));
    }
    @Test void decimalAmountsKeepTwoPlacesWithoutRoundingOrExponentParsing() {
        assertEquals(new BigDecimal("12.30"),FieldTypes.decimal("12.3","Price"));
        assertEquals(new BigDecimal("0.00"),FieldTypes.decimal("0","Price"));
        for(String value:List.of("12.345","12.000","1e3","-1","abc","100000000000000000"))
            assertThrows(IllegalArgumentException.class,()->FieldTypes.decimal(value,"Price"));
    }
}
