package co.rsk.net.light;

import java.util.HashMap;
import java.util.Map;

public enum LightClientMessageCodes {

    STATUS(0x00),

    GET_BLOCK_RECEIPTS(0x01),

    BLOCK_RECEIPTS(0x02),

    GET_TRANSACTION_INDEX(0x03),

    TRANSACTION_INDEX(0x04),

    GET_CODE(0x05),

    CODE(0x06);

    private final int cmd;

    private static final Map<Integer, LightClientMessageCodes> intToTypeMap = new HashMap<>();

    static {
        for (LightClientMessageCodes type : LightClientMessageCodes.values()) {
            intToTypeMap.put(type.cmd, type);
        }
    }

    LightClientMessageCodes(int cmd) {
        this.cmd = cmd;
    }

    public static boolean inRange(byte code) {
        return code >= STATUS.asByte() && code <= BLOCK_RECEIPTS.asByte();
    }

    public static LightClientMessageCodes fromByte(byte i) {
        return intToTypeMap.get((int) i);
    }

    public byte asByte() {
        return (byte) (cmd);
    }
}
