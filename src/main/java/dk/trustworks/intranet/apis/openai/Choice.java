package dk.trustworks.intranet.apis.openai;

public class Choice {
    private int index;
    private Message message;
    private String finish_reason;
    
    // Getters and setters
    public int getIndex() {
        return index;
    }
    
    public void setIndex(int index) {
        this.index = index;
    }
    
    public Message getMessage() {
        return message;
    }
    
    public void setMessage(Message message) {
        this.message = message;
    }
    
    public String getFinish_reason() {
        return finish_reason;
    }
    
    public void setFinish_reason(String finish_reason) {
        this.finish_reason = finish_reason;
    }
}
