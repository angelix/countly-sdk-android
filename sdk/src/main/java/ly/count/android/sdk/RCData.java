package ly.count.android.sdk;

public class RCData {
    public Object value;
    boolean isCurrentUsersData;

    protected RCData(Object givenValue, boolean givenUserState) {
        this.value = givenValue;
        this.isCurrentUsersData = givenUserState;
    }
}
