package simpledb.execution;

import simpledb.common.Type;
import simpledb.storage.Field;
import simpledb.storage.IntField;
import simpledb.storage.Tuple;
import simpledb.storage.TupleDesc;
import simpledb.storage.TupleIterator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Knows how to compute some aggregate over a set of IntFields.
 */
public class IntegerAggregator implements Aggregator {

    private static final long serialVersionUID = 1L;

    /**
     * Aggregate constructor
     * 
     * @param gbfield
     *            the 0-based index of the group-by field in the tuple, or
     *            NO_GROUPING if there is no grouping
     * @param gbfieldtype
     *            the type of the group by field (e.g., Type.INT_TYPE), or null
     *            if there is no grouping
     * @param afield
     *            the 0-based index of the aggregate field in the tuple
     * @param what
     *            the aggregation operator
     */

    private final int gbfield;
    private final Type gbfieldtype;
    private final int afield;
    private final Op what;
    private final Map<Field, int[]> groups;

    public IntegerAggregator(int gbfield, Type gbfieldtype, int afield, Op what) {
        this.gbfield = gbfield;
        this.gbfieldtype = gbfieldtype;
        this.afield = afield;
        this.what = what;
        this.groups = new LinkedHashMap<>();
    }

    /**
     * Merge a new tuple into the aggregate, grouping as indicated in the
     * constructor
     * 
     * @param tup
     *            the Tuple containing an aggregate field and a group-by field
     */
    public void mergeTupleIntoGroup(Tuple tup) {
        Field groupVal = (gbfield == NO_GROUPING) ? null : tup.getField(gbfield);
        int val = ((IntField) tup.getField(afield)).getValue();
        int[] agg = groups.get(groupVal);
        if (agg == null) {
            // [min, max, sum, count]
            agg = new int[]{val, val, val, 1};
            groups.put(groupVal, agg);
        } else {
            agg[0] = Math.min(agg[0], val);
            agg[1] = Math.max(agg[1], val);
            agg[2] += val;
            agg[3] += 1;
        }
    }

    /**
     * Create a OpIterator over group aggregate results.
     * 
     * @return a OpIterator whose tuples are the pair (groupVal, aggregateVal)
     *         if using group, or a single (aggregateVal) if no grouping. The
     *         aggregateVal is determined by the type of aggregate specified in
     *         the constructor.
     */
    public OpIterator iterator() {
        TupleDesc td;
        if (gbfield == NO_GROUPING) {
            td = new TupleDesc(new Type[]{Type.INT_TYPE});
        } else {
            td = new TupleDesc(new Type[]{gbfieldtype, Type.INT_TYPE});
        }
        List<Tuple> tuples = new ArrayList<>();
        for (Map.Entry<Field, int[]> entry : groups.entrySet()) {
            int[] agg = entry.getValue();
            int result;
            switch (what) {
                case MIN:   result = agg[0]; break;
                case MAX:   result = agg[1]; break;
                case SUM:   result = agg[2]; break;
                case COUNT: result = agg[3]; break;
                case AVG:   result = agg[2] / agg[3]; break;
                default: throw new IllegalStateException("Unsupported op: " + what);
            }
            Tuple t = new Tuple(td);
            if (gbfield == NO_GROUPING) {
                t.setField(0, new IntField(result));
            } else {
                t.setField(0, entry.getKey());
                t.setField(1, new IntField(result));
            }
            tuples.add(t);
        }
        return new TupleIterator(td, tuples);
    }

}
