package com.liangledecha.chachacalendar;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 版本化的完整本地备份格式；只使用 Java 标准库，并在解析前校验 SHA-256。 */
public final class BackupCodec {
    private static final int MAGIC = 0x43484342, VERSION = 1, MAX_BYTES = 16 * 1024 * 1024, MAX_EVENTS = 100_000;

    public static final class Data {
        public final List<Event> events;
        public final Map<String, String> settings;
        Data(List<Event> events, Map<String, String> settings) { this.events = events; this.settings = settings; }
    }

    private BackupCodec() { }

    public static byte[] encode(List<Event> events, Map<String, String> settings) throws IOException {
        if (events.size() > MAX_EVENTS) throw new IOException("事项数量超出备份限制");
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payloadBytes)) {
            out.writeInt(events.size());
            for (Event e : events) writeEvent(out, e);
            out.writeInt(settings.size());
            for (Map.Entry<String, String> entry : settings.entrySet()) {
                writeString(out, entry.getKey()); writeString(out, entry.getValue());
            }
        }
        byte[] payload = payloadBytes.toByteArray();
        if (payload.length > MAX_BYTES) throw new IOException("备份文件超过 16 MB");
        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(fileBytes)) {
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(payload.length); out.write(payload); out.write(sha256(payload));
        }
        return fileBytes.toByteArray();
    }

    public static Data decode(InputStream input) throws IOException {
        byte[] file = readLimited(input, MAX_BYTES + 44);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(file))) {
            if (in.readInt() != MAGIC) throw new IOException("不是茶茶日历备份文件");
            if (in.readInt() != VERSION) throw new IOException("暂不支持此备份版本");
            int length = in.readInt();
            if (length < 0 || length > MAX_BYTES || file.length != length + 44) throw new IOException("备份文件长度无效");
            byte[] payload = new byte[length]; in.readFully(payload);
            byte[] expected = new byte[32]; in.readFully(expected);
            if (!MessageDigest.isEqual(expected, sha256(payload))) throw new IOException("备份校验失败，文件可能已损坏");
            return decodePayload(payload);
        }
    }

    private static Data decodePayload(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int count = in.readInt();
            if (count < 0 || count > MAX_EVENTS) throw new IOException("事项数量无效");
            ArrayList<Event> events = new ArrayList<>(count);
            for (int i = 0; i < count; i++) events.add(readEvent(in));
            int settingCount = in.readInt();
            if (settingCount < 0 || settingCount > 64) throw new IOException("设置数量无效");
            HashMap<String, String> settings = new HashMap<>();
            for (int i = 0; i < settingCount; i++) settings.put(readString(in), readString(in));
            if (in.available() != 0) throw new IOException("备份包含无法识别的数据");
            return new Data(Collections.unmodifiableList(events), Collections.unmodifiableMap(settings));
        } catch (RuntimeException error) { throw new IOException("备份内容无效", error); }
    }

    private static void writeEvent(DataOutputStream out, Event e) throws IOException {
        out.writeLong(e.id); writeString(out, e.title); writeString(out, e.date.toString()); writeString(out, e.type);
        out.writeInt(e.visibilityDays); writeString(out, e.repeatRule); writeNullable(out, e.time == null ? null : e.time.toString());
        out.writeBoolean(e.completed); writeNullable(out, e.completedThrough == null ? null : e.completedThrough.toString());
        out.writeBoolean(e.lunarBased); out.writeInt(e.lunarMonth); out.writeInt(e.lunarDay); out.writeBoolean(e.lunarLeapMonth);
        writeNullable(out, e.plannedStart == null ? null : e.plannedStart.toString());
        writeNullable(out, e.plannedEnd == null ? null : e.plannedEnd.toString());
    }

    private static Event readEvent(DataInputStream in) throws IOException {
        long id = in.readLong(); String title = readString(in); LocalDate date = LocalDate.parse(readString(in));
        String type = readString(in); int visibility = in.readInt(); String repeat = readString(in);
        String time = readNullable(in), completedThrough = null; boolean completed = in.readBoolean(); completedThrough = readNullable(in);
        boolean lunar = in.readBoolean(); int lunarMonth = in.readInt(), lunarDay = in.readInt(); boolean leap = in.readBoolean();
        String plannedStart = readNullable(in), plannedEnd = readNullable(in);
        if (id < 0 || title.trim().isEmpty() || visibility < -1 || lunarMonth < 0 || lunarMonth > 12 || lunarDay < 0 || lunarDay > 30
                || !validType(type) || !validRepeat(repeat) || (plannedStart == null) != (plannedEnd == null)) throw new IOException("事项字段无效");
        LocalDateTime start = plannedStart == null ? null : LocalDateTime.parse(plannedStart);
        LocalDateTime end = plannedEnd == null ? null : LocalDateTime.parse(plannedEnd);
        if (start != null && end.isBefore(start)) throw new IOException("甘特时间范围无效");
        Event event = new Event(id, title, date, type, visibility, repeat, time == null ? null : LocalTime.parse(time), completed,
                -1, lunar, lunarMonth, lunarDay, leap, start, end);
        event.completedThrough = completedThrough == null ? null : LocalDate.parse(completedThrough);
        return event;
    }

    private static boolean validType(String value) {
        return "待办".equals(value) || "普通日程".equals(value) || "纪念日".equals(value) || "生日".equals(value) || "倒数日".equals(value);
    }

    private static boolean validRepeat(String value) {
        return Event.NONE.equals(value) || Event.YEARLY.equals(value) || Event.HALF_YEARLY.equals(value)
                || Event.QUARTERLY.equals(value) || Event.MONTHLY.equals(value) || Event.WEEKLY.equals(value) || Event.DAILY.equals(value);
    }

    private static void writeNullable(DataOutputStream out, String value) throws IOException { out.writeBoolean(value != null); if (value != null) writeString(out, value); }
    private static String readNullable(DataInputStream in) throws IOException { return in.readBoolean() ? readString(in) : null; }
    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); if (bytes.length > 1_048_576) throw new IOException("文本字段过长");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt(); if (length < 0 || length > 1_048_576 || length > in.available()) throw new IOException("文本字段长度无效");
        byte[] bytes = new byte[length]; in.readFully(bytes); return new String(bytes, StandardCharsets.UTF_8);
    }
    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int total = 0, read;
        while ((read = in.read(buffer)) != -1) { total += read; if (total > limit) throw new IOException("备份文件过大"); out.write(buffer, 0, read); }
        return out.toByteArray();
    }
    private static byte[] sha256(byte[] bytes) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }
}
