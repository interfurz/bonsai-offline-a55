package de.bonsai.offline;

final class ReasoningText {
    static String strip(String text) {
        StringBuilder clean=new StringBuilder();int from=0;
        while(from<text.length()) {
            int open=text.indexOf("<think>",from),close=text.indexOf("</think>",from);
            if(close>=0 && (open<0 || close<open)) {
                clean.append(text,from,close);from=close+8;
            } else if(open>=0) {
                clean.append(text,from,open);close=text.indexOf("</think>",open+7);
                if(close<0)break;
                from=close+8;
            } else { clean.append(text,from,text.length());break; }
        }
        return clean.toString();
    }
    private ReasoningText() {}
}
