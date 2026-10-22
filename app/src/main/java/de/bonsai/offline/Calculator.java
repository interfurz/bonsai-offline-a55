package de.bonsai.offline;

/** Bounded recursive descent parser; no scripting engine or native dependencies. */
final class Calculator {
    private final String input;
    private int position, depth;
    private Calculator(String input) { this.input=input; }
    static double evaluate(String expression) {
        if(expression==null || expression.length()>1024) throw new IllegalArgumentException("Ausdruck zu lang.");
        Calculator parser=new Calculator(expression);
        double result=parser.sum();
        parser.space();
        if(parser.position!=expression.length()) throw parser.error();
        return finite(result);
    }
    private void space() { while(position<input.length() && Character.isWhitespace(input.charAt(position))) position++; }
    private boolean take(char c) {
        space();
        if(position<input.length() && input.charAt(position)==c) { position++; return true; }
        return false;
    }
    private IllegalArgumentException error() { return new IllegalArgumentException("Ungültiger Ausdruck bei Position "+(position+1)+"."); }
    private static double finite(double value) {
        if(!Double.isFinite(value)) throw new IllegalArgumentException("Ergebnis ist keine endliche Zahl.");
        return value;
    }
    private double sum() {
        double value=product();
        while(true) {
            if(take('+')) value=finite(value+product());
            else if(take('-')) value=finite(value-product());
            else return value;
        }
    }
    private double product() {
        double value=unary();
        while(true) {
            if(take('*')) value=finite(value*unary());
            else if(take('/')) value=finite(value/unary());
            else if(take('%')) value=finite(value%unary());
            else return value;
        }
    }
    private double unary() {
        if(++depth>64) throw new IllegalArgumentException("Ausdruck zu tief verschachtelt.");
        try {
            if(take('+')) return unary();
            if(take('-')) return -unary();
            double value=atom();
            return take('^')?finite(Math.pow(value,unary())):value;
        } finally { depth--; }
    }
    private double atom() {
        if(take('(')) {
            double value=sum();
            if(!take(')')) throw error();
            return value;
        }
        space();
        int start=position;
        if(position<input.length() && Character.isLetter(input.charAt(position)))
            while(position<input.length() && Character.isLetterOrDigit(input.charAt(position))) position++;
        if(position>start) {
            String name=input.substring(start,position);
            if(name.equals("pi")) return Math.PI;
            if(name.equals("e")) return Math.E;
            if(!take('(')) throw error();
            double arg=sum();
            if(!take(')')) throw error();
            switch(name) {
                case "sqrt": return finite(Math.sqrt(arg));
                case "abs": return Math.abs(arg);
                case "sin": return Math.sin(arg);
                case "cos": return Math.cos(arg);
                case "tan": return finite(Math.tan(arg));
                case "log": return finite(Math.log(arg));
                case "log10": return finite(Math.log10(arg));
                default: throw new IllegalArgumentException("Unbekannte Funktion: "+name);
            }
        }
        while(position<input.length() && (Character.isDigit(input.charAt(position)) || input.charAt(position)=='.')) position++;
        if(position<input.length() && (input.charAt(position)=='e' || input.charAt(position)=='E')) {
            position++;
            if(position<input.length() && (input.charAt(position)=='+' || input.charAt(position)=='-')) position++;
            while(position<input.length() && Character.isDigit(input.charAt(position))) position++;
        }
        try { return finite(Double.parseDouble(input.substring(start,position))); }
        catch(NumberFormatException e) { throw error(); }
    }
}
