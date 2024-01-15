package dev.langchain4j.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.internal.Json;
import dev.langchain4j.model.output.*;
import dev.langchain4j.model.output.structured.Description;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static dev.langchain4j.exception.IllegalConfigurationException.illegalConfiguration;
import static java.lang.String.format;
import static java.util.Arrays.asList;

public class ServiceOutputParser {

    private static final Map<Class<?>, OutputParser<?>> OUTPUT_PARSERS = new HashMap<>();

    static {
        OUTPUT_PARSERS.put(boolean.class, new BooleanOutputParser());
        OUTPUT_PARSERS.put(Boolean.class, new BooleanOutputParser());

        OUTPUT_PARSERS.put(byte.class, new ByteOutputParser());
        OUTPUT_PARSERS.put(Byte.class, new ByteOutputParser());

        OUTPUT_PARSERS.put(short.class, new ShortOutputParser());
        OUTPUT_PARSERS.put(Short.class, new ShortOutputParser());

        OUTPUT_PARSERS.put(int.class, new IntOutputParser());
        OUTPUT_PARSERS.put(Integer.class, new IntOutputParser());

        OUTPUT_PARSERS.put(long.class, new LongOutputParser());
        OUTPUT_PARSERS.put(Long.class, new LongOutputParser());

        OUTPUT_PARSERS.put(BigInteger.class, new BigIntegerOutputParser());

        OUTPUT_PARSERS.put(float.class, new FloatOutputParser());
        OUTPUT_PARSERS.put(Float.class, new FloatOutputParser());

        OUTPUT_PARSERS.put(double.class, new DoubleOutputParser());
        OUTPUT_PARSERS.put(Double.class, new DoubleOutputParser());

        OUTPUT_PARSERS.put(BigDecimal.class, new BigDecimalOutputParser());

        OUTPUT_PARSERS.put(Date.class, new DateOutputParser());
        OUTPUT_PARSERS.put(LocalDate.class, new LocalDateOutputParser());
        OUTPUT_PARSERS.put(LocalTime.class, new LocalTimeOutputParser());
        OUTPUT_PARSERS.put(LocalDateTime.class, new LocalDateTimeOutputParser());
    }

    public static Object parse(Response<AiMessage> response, Class<?> returnType) {

        if (returnType == Response.class) {
            return response;
        }

        AiMessage aiMessage = response.content();
        if (returnType == AiMessage.class) {
            return aiMessage;
        }

        String text = aiMessage.text();
        if (returnType == String.class) {
            return text;
        }

        OutputParser<?> outputParser = OUTPUT_PARSERS.get(returnType);
        if (outputParser != null) {
            return outputParser.parse(text);
        }

        if (returnType == List.class) {
            return asList(text.split("\n"));
        }

        if (returnType == Set.class) {
            return new HashSet<>(asList(text.split("\n")));
        }

        return Json.fromJson(text, returnType);
    }

    public static String outputFormatInstructions(Class<?> returnType) {

        if (returnType == String.class
                || returnType == AiMessage.class
                || returnType == TokenStream.class
                || returnType == Response.class) {
            return "";
        }

        if (returnType == void.class) {
            throw illegalConfiguration("Return type of method '%s' cannot be void");
        }

        if (returnType.isEnum()) {
            String formatInstructions = new EnumOutputParser(returnType.asSubclass(Enum.class)).formatInstructions();
            return "\nYou must answer strictly in the following format: " + formatInstructions;
        }

        OutputParser<?> outputParser = OUTPUT_PARSERS.get(returnType);
        if (outputParser != null) {
            String formatInstructions = outputParser.formatInstructions();
            return "\nYou must answer strictly in the following format: " + formatInstructions;
        }

        if (returnType == List.class || returnType == Set.class) {
            return "\nYou must put every item on a separate line.";
        }

        return "\nYou must answer strictly in the following JSON format: " + jsonStructure(returnType);
    }

    private static String jsonStructure(Class<?> structured) {
        StringBuilder jsonSchema = new StringBuilder();
        jsonSchema.append("{\n");
        for (Field field : structured.getDeclaredFields()) {

            Type genericType = field.getGenericType();

            if (!(genericType instanceof ParameterizedType)) {
                if (field.getType().getPackage() == null || field.getType().getPackage().getName().startsWith("java.")) {
                    // This is a standard Java type.
                    jsonSchema.append(format("\"%s\": (%s),\n", field.getName(), descriptionFor(field)));
                } else if (!field.getName().contains("this")){
                    // This is a custom Java type.
                    jsonSchema.append(format("\"%s\": (%s) ", field.getName(), descriptionFor(field)));
                    jsonStructureCustomType(jsonSchema, field.getType());
                }
                continue;
            }

            ParameterizedType pType = (ParameterizedType) genericType;

            if (!(pType.getRawType().equals(List.class) || pType.getRawType().equals(Set.class))) {
                //TODO: This is not a List or Set type. If necessary, you can add more conditions here to handle other ParameterizedType.
                continue;
            }

            Type[] fieldArgsTypes = pType.getActualTypeArguments();

            for(Type oneArgType : fieldArgsTypes){
                if (oneArgType.getTypeName().startsWith("java.")) {
                    // This is a list field contains built-in Java type
                    jsonSchema.append(format("\"%s\": (%s),\n", field.getName(), descriptionFor(field)));
                } else if (!oneArgType.getTypeName().contains("this")) {
                    // This is a list field with custom Java type.
                    jsonSchema.append(format("\"%s\": (%s) ", field.getName(), descriptionFor(field)));
                    jsonStructureCustomTypeArray(jsonSchema, oneArgType);
                }
            }
        }
        jsonSchema.append("}");
        return jsonSchema.toString();
    }

    private static void jsonStructureCustomType(StringBuilder jsonSchema, Class type) {
        jsonSchema.append("{\n");

        Field[] fields = type.getDeclaredFields();
        for (Field field : fields) {
            if (field.getType().getPackage() == null || field.getType().getPackage().getName().startsWith("java.")) {
                // This is a standard Java type.
                jsonSchema.append(format("\"%s\": (%s),\n", field.getName(), descriptionFor(field)));
            } else if (!field.getName().contains("this")) {
                // This is a custom Java type.
                jsonSchema.append(format("\"%s\": (%s) ", field.getName(), descriptionFor(field)));
                jsonStructureCustomType(jsonSchema, field.getType());
            }
        }
        jsonSchema.append("}");
    }

    private static void jsonStructureCustomTypeArray(StringBuilder jsonSchema, Type customType) {
        jsonSchema.append("[\n");

        if (!customType.getTypeName().contains("this")) {
            // This is a custom Java type.
            Class<?> genericClass = null;
            try {
                genericClass = Class.forName(customType.getTypeName());
                jsonStructureCustomType(jsonSchema, genericClass);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
        jsonSchema.append("]");
    }

    private static String descriptionFor(Field field) {
        Description fieldDescription = field.getAnnotation(Description.class);
        if (fieldDescription == null) {
            return "type: " + typeOf(field);
        }

        return String.join(" ", fieldDescription.value()) + "; type: " + typeOf(field);
    }

    private static String typeOf(Field field) {
        Type type = field.getGenericType();

        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            Type[] typeArguments = parameterizedType.getActualTypeArguments();

            if (parameterizedType.getRawType().equals(List.class)
                    || parameterizedType.getRawType().equals(Set.class)) {
                return format("array of %s", simpleTypeName(typeArguments[0]));
            }
        } else if (field.getType().isArray()) {
            return format("array of %s", simpleTypeName(field.getType().getComponentType()));
        } else if (((Class<?>) type).isEnum()) {
            return "enum, must be one of " + Arrays.toString(((Class<?>) type).getEnumConstants());
        }

        return simpleTypeName(type);
    }

    private static String simpleTypeName(Type type) {
        switch (type.getTypeName()) {
            case "java.lang.String":
                return "string";
            case "java.lang.Integer":
            case "int":
                return "integer";
            case "java.lang.Boolean":
            case "boolean":
                return "boolean";
            case "java.lang.Float":
            case "float":
                return "float";
            case "java.lang.Double":
            case "double":
                return "double";
            case "java.util.Date":
            case "java.time.LocalDate":
                return "date string (2023-12-31)";
            case "java.time.LocalTime":
                return "time string (23:59:59)";
            case "java.time.LocalDateTime":
                return "date-time string (2023-12-31T23:59:59)";
            default:
                return "Object";
        }
    }
}
