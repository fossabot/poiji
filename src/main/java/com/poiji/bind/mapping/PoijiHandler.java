package com.poiji.bind.mapping;

import com.poiji.annotation.ExcelCell;
import com.poiji.annotation.ExcelCellName;
import com.poiji.annotation.ExcelCellRange;
import com.poiji.annotation.ExcelRow;
import com.poiji.config.Casting;
import com.poiji.exception.IllegalCastException;
import com.poiji.option.PoijiOptions;
import com.poiji.util.ReflectUtil;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.usermodel.XSSFComment;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static java.lang.String.valueOf;

/**
 * This class handles the processing of a .xlsx file,
 * and generates a list of instances of a given type
 * <p>
 * Created by hakan on 22/10/2017
 */
final class PoijiHandler<T> implements SheetContentsHandler {

    private T instance;
    private Consumer<? super T> consumer;
    private int internalRow;
    private int internalCount;
    private int limit;

    private Class<T> type;
    private PoijiOptions options;

    private final Casting casting;
    private Map<String, Integer> titles;
    // New maps used to speed up computing and handle inner objects
    private Map<String, Object> fieldInstances;
    private Map<Integer, Field> columnToField;
    private Map<Integer, Field> columnToSuperClassField;

    PoijiHandler(Class<T> type, PoijiOptions options, Consumer<T> consumer) {
        this.type = type;
        this.options = options;
        this.consumer = consumer;
        this.limit = options.getLimit();

        casting = options.getCasting();
        titles = new HashMap<>();
        columnToField = new HashMap<>();
        columnToSuperClassField = new HashMap<>();
    }

    private void setFieldValue(String content, Class<? super T> subclass, int column) {
        if (subclass != Object.class) {
            if(setValue(content, subclass, column)) {
                return;
            }

            setFieldValue(content, subclass.getSuperclass(), column);
        }
    }
    /**
     *  Using this to hold inner objects that will be mapped to the main object
     * **/
    private Object getInstance(Field field) {
        Object ins = null;
        if (fieldInstances.containsKey(field.getName())) {
            ins = fieldInstances.get(field.getName());
        } else {
            ins = ReflectUtil.newInstanceOf(field.getType());
            fieldInstances.put(field.getName(), ins);
        }
        return ins;
    }

    private boolean setValue(String content, Class<? super T> type, int column) {

        for (Field field : type.getDeclaredFields()) {
            ExcelRow excelRow = field.getAnnotation(ExcelRow.class);
            if (excelRow != null) {
                Object o = casting.castValue(field.getType(), valueOf(internalCount), options);
                setFieldData(field, o, instance);
                columnToField.put(-1, field);
            }
            ExcelCellRange range = field.getAnnotation(ExcelCellRange.class);
            if (range != null) {
                Object ins = null;
                ins = getInstance(field);
                for (Field f : field.getType().getDeclaredFields()) {
                    if (setValue(f, column, content, ins)) {
                        setFieldData(field, ins, instance);
                        columnToField.put(column, f);
                        columnToSuperClassField.put(column, field);
//                        return true;
                    }
                }
            } else {
                if(setValue(field, column, content, instance)) {
                    columnToField.put(column, field);
//                    return true;
                }
            }
        }

        // For ExcelRow annotation
        if(columnToField.containsKey(-1)) {
            Field field = columnToField.get(-1);
            Object o = casting.castValue(field.getType(), valueOf(internalCount), options);
            setFieldData(field, o, instance);
        }
        if(columnToField.containsKey(column)) {
            Field field = columnToField.get(column);
            if (columnToSuperClassField.containsKey(column)) {
                Object ins;
                ins = getInstance(columnToSuperClassField.get(column));
                if (setValue(field, column, content, ins)) {
                    setFieldData(columnToSuperClassField.get(column), ins, instance);
                    return true;
                }
            }
            return setValue(field, column, content, instance);
        }

        return false;
    }

    private boolean setValue(Field field, int column, String content, Object ins) {
        ExcelCell index = field.getAnnotation(ExcelCell.class);
        if (index != null) {
            Class<?> fieldType = field.getType();
            if (column == index.value()) {
                Object o = casting.castValue(fieldType, content, options);
                setFieldData(field, o, ins);
            }
        } else {
            ExcelCellName excelCellName = field.getAnnotation(ExcelCellName.class);
            if (excelCellName != null) {
                Class<?> fieldType = field.getType();
                Integer titleColumn = titles.get(excelCellName.value() );
                //Fix both columns mapped to name passing this condition below
                if (titleColumn != null && titleColumn == column) {
                    Object o = casting.castValue(fieldType, content, options);
                    setFieldData(field, o, ins);
                    return true;
                }
            }
        }
        return false;
    }

    private void setFieldData(Field field, Object o, Object instance) {
        try {
            field.setAccessible(true);
            field.set(instance, o);
        } catch (IllegalAccessException e) {
            throw new IllegalCastException("Unexpected cast type {" + o + "} of field" + field.getName());
        }
    }

    @Override
    public void startRow(int rowNum) {
        if (rowNum + 1 > options.skip()) {
            internalCount += 1;
            instance = ReflectUtil.newInstanceOf(type);
            fieldInstances = new HashMap<>();
        }
    }

    @Override
    public void endRow(int rowNum) {

        if (internalRow != rowNum)
			return;

        if (rowNum + 1 > options.skip()) {
            consumer.accept(instance);
        }
    }

    @Override
    public void cell(String cellReference, String formattedValue, XSSFComment comment) {
        CellAddress cellAddress = new CellAddress(cellReference);
        int row = cellAddress.getRow();

        int headers = options.getHeaderStart();
        int column = cellAddress.getColumn();

        if (row <= headers) {
            titles.put(formattedValue, column);
        }

        if (row + 1 <= options.skip()) {
            return;
        }

        if (limit != 0 && internalCount > limit) {
            return;
        }

        internalRow = row;

        setFieldValue(formattedValue, type, column);
    }

    @Override
    public void headerFooter(String text, boolean isHeader, String tagName) {
        //no-op
    }
}
