package simpledb.common;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;

/**
 * Interface for values of fields in database records (Tuples).
 */
public interface Field extends Serializable {
    /**
     * Write the bytes representing this field to the specified DataOutputStream.
     * @param dos The stream to write to
     * @throws IOException if an I/O error occurs
     */
    void serialize(DataOutputStream dos) throws IOException;

    /**
     * Compare this field to another field.
     * @param op The comparison operator
     * @param val The field to compare against
     * @return true if the comparison holds
     */
    boolean compare(Op op, Field val);

    /**
     * Returns the type of this field.
     * @return The field's type
     */
    Type getType();

    /**
     * Supported operators for comparing fields.
     */
    enum Op {
        EQUALS, GREATER_THAN, LESS_THAN, LESS_THAN_OR_EQ, GREATER_THAN_OR_EQ, LIKE, NOT_EQUALS
    }
}
