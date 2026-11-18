package com.inventory.preview;

/** Numeric controls advertise their type; Java remains the final validation boundary. */
final class NumericFields {
    private NumericFields() { }
    static String render(String label,String name,String value,String attributes) {
        boolean decimal=attributes.contains("step='0.01'");
        if(!attributes.contains("step=")) attributes+=" step='1'";
        if(!attributes.contains("max=")) attributes+=decimal?" max='99999999999999999.99'":" max='2147483647'";
        return "<label>"+InventoryPage.html(label)+"<input required type='number' name='"+name+"' value='"+InventoryPage.html(value)
                +"' aria-label='"+InventoryPage.html(label)+"' data-value-type='"+(decimal?"decimal":"integer")+"' data-field-label='"+InventoryPage.html(label)+"' inputmode='"+(decimal?"decimal":"numeric")+"' "+attributes
                +"><small class='field-hint'>"+(decimal?"Up to 2 decimal places":"Whole numbers only")+"</small></label>";
    }
}
