package simpledb.common;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Instance of Field that stores a fixed-length string.
 */
public class StringField implements Field {
    private static final long serialVersionUID = 1L;

    private final String value;
    private final int maxSize;

    public String getValue() {
        return value;
    }

    /**
     * Constructor.
     * @param s The string value
     * @param maxSize Maximum length of this string
     */
    public StringField(String s, int maxSize) {
        this.maxSize = maxSize;
        if (s.length() > maxSize) {
            this.value = s.substring(0, maxSize);
        } else {
            this.value = s;
        }
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof StringField) {
            return ((StringField) o).value.equals(value);
        }
        return false;
    }

    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        String s = value;
        int overflow = maxSize - s.length();
        if (overflow < 0) {
            s = s.substring(0, maxSize);
        }
        dos.writeInt(s.length());
        dos.writeBytes(s);
        while (overflow > 0) {
            dos.writeByte(0);
            overflow--;
        }
    }

    @Override
    public boolean compare(Op op, Field val) {
        StringField sVal = (StringField) val;
        int cmpVal = value.compareTo(sVal.value);

        switch (op) {
            case EQUALS:
                return cmpVal == 0;
            case NOT_EQUALS:
                return cmpVal != 0;
            case GREATER_THAN:
                return cmpVal > 0;
            case GREATER_THAN_OR_EQ:
                return cmpVal >= 0;
            case LESS_THAN:
                return cmpVal < 0;
            case LESS_THAN_OR_EQ:
                return cmpVal <= 0;
            case LIKE:
                return value.contains(sVal.value);
            default:
                return false;
        }
    }

    @Override
    public Type getType() {
        return Type.STRING_TYPE;
    }
}
