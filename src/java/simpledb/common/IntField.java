package simpledb.common;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Instance of Field that stores a 32-bit integer.
 */
public class IntField implements Field {
    private static final long serialVersionUID = 1L;
    
    private final int value;

    public int getValue() {
        return value;
    }

    public IntField(int i) {
        value = i;
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }

    @Override
    public int hashCode() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof IntField) {
            return ((IntField) o).value == value;
        }
        return false;
    }

    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        dos.writeInt(value);
    }

    @Override
    public boolean compare(Op op, Field val) {
        IntField iVal = (IntField) val;

        switch (op) {
            case EQUALS:
                return value == iVal.value;
            case NOT_EQUALS:
                return value != iVal.value;
            case GREATER_THAN:
                return value > iVal.value;
            case GREATER_THAN_OR_EQ:
                return value >= iVal.value;
            case LESS_THAN:
                return value < iVal.value;
            case LESS_THAN_OR_EQ:
                return value <= iVal.value;
            default:
                return false;
        }
    }

    @Override
    public Type getType() {
        return Type.INT_TYPE;
    }
}
