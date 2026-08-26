package com.liangledecha.chachacalendar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.NumberPicker;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 应用主页面和界面协调中心。
 *
 * <p>本类负责组装顶部工具栏、年视图、月视图、事项列表和底部导航，
 * 接收用户操作后调用数据库、刷新列表，并把变更同步到桌面小组件。</p>
 */
public final class MainActivity extends Activity {
    /** 主内容区当前页面类型。日程和待办共用事项列表，通过额外开关区分。 */
    private enum Section { YEAR, MONTH, AGENDA }
    /** 全应用主蓝色，供选中状态、圆形按钮和强调文字使用。 */
    private final int accent = Color.rgb(82, 110, 240);
    /** 本地日程数据库入口。 */
    private EventStore store;
    /** 三态月历控件。 */
    private MonthCalendarView calendar;
    /** 顶部年月标题，点击后打开年月滚轮。 */
    private TextView monthTitle;
    /** 列表上方的“今天＋日期”或页面说明文字。 */
    private TextView summary;
    /** 下半区事项列表。 */
    private ListView list;
    /** 把日程对象转换为事项卡片的列表适配器。 */
    private EventAdapter adapter;
    /** 包含摘要和列表的下半区容器。 */
    private LinearLayout lowerPanel;
    /** 保存周数开关和示例数据初始化状态的轻量设置存储。 */
    private SharedPreferences prefs;
    /** 十二个月全年总览控件。 */
    private YearCalendarView yearCalendar;
    /** 顶部年、月两个页面切换按钮。 */
    private TextView yearTab, monthTab;
    /** 底部日历、日程、待办三个文字导航按钮。 */
    private TextView bottomCalendar, bottomAgenda, bottomTodo;
    /** 当前页面，默认进入月视图。 */
    private Section section = Section.MONTH;
    /** 为真时事项列表只显示“待办”类型。 */
    private boolean todosOnly;
    /** 正在下载的节假日年份，防止快速滑动月份时重复请求同一年。 */
    private final Set<Integer> holidayYearsLoading = new HashSet<>();
    /** 月历绘制使用的事项缓存；只在事项真正增删改时读取数据库，换月时直接复用。 */
    private List<Event> calendarEventCache = new ArrayList<>();

    /**
     * 页面创建入口。
     * 依次初始化数据库、设置、系统栏颜色、界面和初始数据，并应用系统安全区。
     */
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new EventStore(this);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        seedSamplesIfEmpty();
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.rgb(248, 249, 252));
        View content = buildUi();
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(content);
        refresh();
        requestCalendarPermissionIfNeeded();
    }

    /** 从其他页面返回应用时，如果正停留在待办页，再检查一次已办结事项是否刚刚过期。 */
    @Override protected void onResume() {
        super.onResume();
        if (todosOnly && list != null) list.post(this::promptNextExpiredCompletedTodo);
    }

    /**
     * 接收系统日历权限请求结果。
     * 授权后会在后台补同步全部已有事项；拒绝后仍可正常使用本地日历功能。
     */
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != SystemCalendarSync.PERMISSION_REQUEST) return;
        if (SystemCalendarSync.hasPermission(this)) {
            syncAllToSystem();
        } else {
            Toast.makeText(this, "未获得系统日历权限，事项会保存在茶茶日历本地，但系统日历无法提醒。", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 使用原生控件从上到下组装完整页面。
     * 返回的根容器同时承载主内容和悬浮在底部的导航栏。
     */
    private View buildUi() {
        // 第一步：创建覆盖全屏的根容器，并设置全应用浅色背景。
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(248, 249, 252));
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(10), dp(16), dp(8));
        FrameLayout.LayoutParams pageParams = new FrameLayout.LayoutParams(-1, -1);
        pageParams.bottomMargin = dp(76);
        root.addView(page, pageParams);

        // 第二步：创建顶部工具栏，左侧是年月标题，右侧是更多菜单。
        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        monthTitle = text("", 25, Color.rgb(28,34,48), true);
        monthTitle.setGravity(Gravity.CENTER_VERTICAL);
        monthTitle.setOnClickListener(v -> { if (section != Section.AGENDA) showYearMonthPicker(); });
        toolbar.addView(monthTitle, new LinearLayout.LayoutParams(0, dp(54), 1));
        Button menu = smallButton("⋮"); menu.setTextSize(25); menu.setOnClickListener(this::showMenu);
        toolbar.addView(menu);
        page.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(58)));

        // 第三步：创建年、月两个顶部分页按钮。
        LinearLayout tabs = new LinearLayout(this); tabs.setGravity(Gravity.CENTER);
        yearTab = makeTab("年", Section.YEAR); monthTab = makeTab("月", Section.MONTH);
        tabs.addView(yearTab, new LinearLayout.LayoutParams(0, dp(40), 1));
        tabs.addView(monthTab, new LinearLayout.LayoutParams(0, dp(40), 1));
        page.addView(tabs);

        // 第四步：加入全年总览。默认隐藏，点击“年”后占据主要内容空间。
        yearCalendar = new YearCalendarView(this);
        yearCalendar.setVisibility(View.GONE);
        yearCalendar.setListener(new YearCalendarView.Listener() {
            @Override public void onYearChanged(int year) { updateTitle(); }
            @Override public void onMonthSelected(YearMonth selectedMonth) {
                calendar.setMonth(selectedMonth); switchSection(Section.MONTH);
            }
        });
        page.addView(yearCalendar, new LinearLayout.LayoutParams(-1, 0, 1));

        // 第五步：加入三态月历，并把日期、状态和月份变化回调连接到页面刷新逻辑。
        calendar = new MonthCalendarView(this);
        calendar.setShowWeekNumbers(prefs.getBoolean("week_numbers", true));
        calendar.setShowLunarDates(prefs.getBoolean("show_lunar_dates", true));
        calendar.setListener(new MonthCalendarView.Listener() {
            @Override public void onDateSelected(LocalDate date) { if (section == Section.MONTH) updateMonthList(); updateTitle(); }
            @Override public void onModeChanged(MonthCalendarView.Mode mode) {
                lowerPanel.setVisibility(mode == MonthCalendarView.Mode.EXPANDED ? View.GONE : View.VISIBLE);
            }
            @Override public void onPeriodChanged() {
                // 换月不重新查询数据库；仅按新月份重建四十二天的日期索引。
                calendar.setEvents(calendarEventCache); refreshCalendarCulture();
                requestHolidayUpdate(calendar.getMonth().getYear(), false, false);
                if (section == Section.MONTH) updateMonthList(); updateTitle();
            }
        });
        page.addView(calendar, new LinearLayout.LayoutParams(-1, -2));

        // 第六步：创建下半区摘要和可滚动事项列表。
        lowerPanel = new LinearLayout(this); lowerPanel.setOrientation(LinearLayout.VERTICAL);
        lowerPanel.setPadding(dp(2), 0, dp(2), 0);
        summary = text("", 15, Color.rgb(38,45,62), true); summary.setGravity(Gravity.CENTER_VERTICAL);
        lowerPanel.addView(summary, new LinearLayout.LayoutParams(-1, dp(42)));
        list = new ListView(this); list.setDividerHeight(0); list.setSelector(android.R.color.transparent);
        adapter = new EventAdapter(this, new ArrayList<>(), this::toggleTodo); list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> showEventDialog(adapter.getItem(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> { confirmDelete(adapter.getItem(pos)); return true; });
        lowerPanel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        page.addView(lowerPanel, new LinearLayout.LayoutParams(-1, 0, 1));

        // 第七步：创建悬浮底栏，五个入口使用统一间距并避让系统导航区。
        LinearLayout bottom = new LinearLayout(this); bottom.setGravity(Gravity.CENTER); bottom.setPadding(dp(8), dp(7), dp(8), dp(7));
        GradientDrawable bottomBg = new GradientDrawable(); bottomBg.setColor(Color.WHITE); bottomBg.setCornerRadius(dp(24)); bottom.setBackground(bottomBg); bottom.setElevation(dp(10));
        Button today = circleButton("今"); today.setOnClickListener(v -> { todosOnly=false; switchSection(Section.MONTH); calendar.today(); updateTabStyles(); });
        bottomCalendar = bottomItem("日历", true); bottomCalendar.setOnClickListener(v -> { todosOnly = false; switchSection(Section.MONTH); updateTabStyles(); });
        bottomAgenda = bottomItem("日程", false); bottomAgenda.setOnClickListener(v -> { todosOnly=false; switchSection(Section.AGENDA); updateTabStyles(); });
        bottomTodo = bottomItem("待办", false); bottomTodo.setOnClickListener(v -> showTodoPage());
        bottom.addView(today, bottomCircleParams());
        bottom.addView(bottomCalendar, bottomItemParams());
        bottom.addView(bottomAgenda, bottomItemParams());
        bottom.addView(bottomTodo, bottomItemParams());
        Button add = circleButton("＋"); add.setTextSize(27); add.setOnClickListener(v -> showEventDialog(null));
        bottom.addView(add, bottomCircleParams());
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1, dp(68), Gravity.BOTTOM);
        bottomParams.setMargins(dp(14), 0, dp(14), dp(4)); root.addView(bottom, bottomParams);
        return root;
    }

    /** 创建一个顶部页面切换按钮，并绑定目标页面。 */
    private TextView makeTab(String label, Section target) {
        TextView tab = text(label, 15, target == Section.MONTH ? accent : Color.rgb(94,102,121), target == Section.MONTH);
        tab.setGravity(Gravity.CENTER); tab.setOnClickListener(v -> { switchSection(target); updateTabStyles(); }); return tab;
    }

    /** 创建底部文字导航按钮，并根据是否选中设置初始样式。 */
    private TextView bottomItem(String label, boolean selected) {
        TextView item = text(label, 14, selected ? accent : Color.rgb(82,89,106), selected); item.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(selected ? Color.rgb(238,241,255) : Color.TRANSPARENT); bg.setCornerRadius(dp(18)); item.setBackground(bg); return item;
    }

    /** 创建底部“今”或加号使用的蓝色圆形按钮。 */
    private Button circleButton(String label) {
        Button button = new Button(this); button.setText(label); button.setTextSize(19); button.setTextColor(Color.WHITE); button.setGravity(Gravity.CENTER);
        button.setMinWidth(0); button.setMinimumHeight(0); button.setPadding(0,0,0,0);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(accent); bg.setShape(GradientDrawable.OVAL); button.setBackground(bg); return button;
    }

    /** 为圆形按钮生成统一尺寸和左右间距。 */
    private LinearLayout.LayoutParams bottomCircleParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48)); params.setMargins(dp(4), 0, dp(4), 0); return params;
    }

    /** 为三个文字导航生成等宽权重和统一左右间距。 */
    private LinearLayout.LayoutParams bottomItemParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1); params.setMargins(dp(4), 0, dp(4), 0); return params;
    }

    /**
     * 切换主内容页面。
     * 方法会控制年历、月历和列表的可见性，准备相应数据并播放淡入位移动画。
     */
    private void switchSection(Section target) {
        if (section == target) {
            if (target == Section.AGENDA) { replaceForAgenda(); updateSummary(); updateTitle(); }
            return;
        }
        section = target;
        yearCalendar.setVisibility(target == Section.YEAR ? View.VISIBLE : View.GONE);
        calendar.setVisibility(target == Section.MONTH ? View.VISIBLE : View.GONE);
        lowerPanel.setVisibility(target == Section.YEAR || (target == Section.MONTH && calendar.getMode() == MonthCalendarView.Mode.EXPANDED) ? View.GONE : View.VISIBLE);
        if (target == Section.AGENDA) {
            replaceForAgenda(); updateSummary();
        } else if (target == Section.MONTH) {
            updateMonthList();
        }
        View visible = target == Section.YEAR ? yearCalendar : target == Section.MONTH ? calendar : lowerPanel;
        visible.setAlpha(.25f); visible.setTranslationX(dp(18));
        visible.animate().alpha(1f).translationX(0).setDuration(260).start();
        updateTabStyles(); updateTitle();
    }

    /** 根据当前页面和待办筛选状态统一刷新顶部及底部按钮样式。 */
    private void updateTabStyles() {
        styleTab(yearTab, section == Section.YEAR); styleTab(monthTab, section == Section.MONTH);
        styleBottom(bottomCalendar, section != Section.AGENDA); styleBottom(bottomAgenda, section == Section.AGENDA && !todosOnly); styleBottom(bottomTodo, section == Section.AGENDA && todosOnly);
    }

    /** 设置一个顶部按钮的颜色和字重。 */
    private void styleTab(TextView tab, boolean selected) {
        tab.setTextColor(selected ? accent : Color.rgb(94,102,121)); tab.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    /** 设置一个底部文字按钮的颜色、字重和圆角选中背景。 */
    private void styleBottom(TextView item, boolean selected) {
        item.setTextColor(selected ? accent : Color.rgb(82,89,106)); item.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(selected ? Color.rgb(238,241,255) : Color.TRANSPARENT); bg.setCornerRadius(dp(18)); item.setBackground(bg);
    }

    /** 根据年、月、日程或待办页面生成顶部标题。 */
    private void updateTitle() {
        if (section == Section.YEAR) monthTitle.setText(String.format(Locale.CHINA, "%d年  ▾", yearCalendar.getYear()));
        else if (section == Section.AGENDA) monthTitle.setText(todosOnly ? "待办" : "日程");
        else monthTitle.setText(String.format(Locale.CHINA, "%d年%d月  ▾", calendar.getMonth().getYear(), calendar.getMonth().getMonthValue()));
    }

    /** 在新增、修改或删除事项后重新读取数据库并刷新所有相关界面。 */
    private void refresh() {
        // 事项发生增删改时才更新缓存，避免每次左右换月都重复解析整张数据表。
        calendarEventCache = store.calendarItems(LocalDate.now());
        calendar.setEvents(calendarEventCache);
        refreshCalendarCulture();
        requestHolidayUpdate(calendar.getMonth().getYear(), false, false);
        if (section == Section.AGENDA) replaceForAgenda(); else updateMonthList();
        updateSummary();
        updateTitle();
        updateWidgets();
    }

    /** 更新列表上方的日期或当前列表类型说明。 */
    private void updateSummary() {
        LocalDate now = LocalDate.now();
        if (section == Section.AGENDA) {
            summary.setText(todosOnly ? "待办  ·  点击修改，长按删除" : "全部日程  ·  按下一次发生日期排列");
            return;
        }
        LocalDate selected = calendar == null ? now : calendar.getSelectedDate();
        long difference = ChronoUnit.DAYS.between(now, selected);
        String dateText = selected.getYear() == now.getYear()
                ? String.format(Locale.CHINA, "%d月%d日", selected.getMonthValue(), selected.getDayOfMonth())
                : String.format(Locale.CHINA, "%d年%d月%d日", selected.getYear(), selected.getMonthValue(), selected.getDayOfMonth());
        summary.setText(difference == 0 ? "今天  " + dateText
                : difference > 0 ? difference + "天后  " + dateText : (-difference) + "天前  " + dateText);
    }

    /** 以月历选中日期为基准，严格按提前显示天数生成下半区列表。 */
    private void updateMonthList() {
        if (calendar == null || adapter == null) return;
        LocalDate anchor = calendar.getSelectedDate();
        adapter.setTodoMode(false);
        adapter.replace(store.visible(anchor, false), anchor); updateSummary();
    }

    /** 为日程页或待办页装载相应数据，列表倒计时统一以今天计算。 */
    private void replaceForAgenda() {
        adapter.setTodoMode(todosOnly);
        adapter.replace(todosOnly ? todoEvents() : store.calendarItems(LocalDate.now()), LocalDate.now());
    }

    /** 从全部事项中筛出类型为“待办”的记录。 */
    private List<Event> todoEvents() {
        ArrayList<Event> result = new ArrayList<>(); for (Event e : store.all()) if ("待办".equals(e.type)) result.add(e); return result;
    }

    /** 打开待办专用列表并刷新底部选中状态。 */
    private void showTodoPage() {
        todosOnly = true; switchSection(Section.AGENDA); updateTabStyles();
        // 页面绘制完成后检查已经办结但随后到期的事项，避免与切换动画争用窗口焦点。
        list.post(this::promptNextExpiredCompletedTodo);
    }

    /**
     * 响应待办左侧选择框的变化。
     * 完成后保留在待办页并变灰，同时从月历、日程、小组件和系统日历中移除；取消勾选则恢复。
     */
    private void toggleTodo(Event event, boolean completed) {
        if (!event.isTodo() || event.completed == completed) return;
        boolean overdueBeforeCompletion = event.isOverdue();
        event.completed = completed;
        store.setCompleted(event.id, completed);
        if (completed) {
            long oldSystemId = event.systemEventId;
            event.systemEventId = -1;
            store.setSystemEventId(event.id, -1);
            new Thread(() -> SystemCalendarSync.delete(getApplicationContext(), oldSystemId), "删除已完成待办提醒").start();
        } else {
            // 恢复未完成后允许它在将来再次过期并重新询问。
            prefs.edit().remove(expiredPromptKey(event.id)).apply();
            syncOneToSystem(event, false);
        }
        refresh();
        if (completed && overdueBeforeCompletion) list.post(() -> promptDeleteExpiredCompleted(event));
    }

    /** 在待办页寻找一条已完成且已经过期、尚未询问过的事项。 */
    private void promptNextExpiredCompletedTodo() {
        if (!todosOnly || section != Section.AGENDA) return;
        for (Event event : store.all()) {
            if (event.isTodo() && event.completed && event.isOverdue()
                    && !prefs.getBoolean(expiredPromptKey(event.id), false)) {
                promptDeleteExpiredCompleted(event); return;
            }
        }
    }

    /** 询问是否删除一条已经办结并过期的待办；保留后不在每次刷新时重复打扰。 */
    private void promptDeleteExpiredCompleted(Event event) {
        if (event == null || event.id <= 0) return;
        prefs.edit().putBoolean(expiredPromptKey(event.id), true).apply();
        new AlertDialog.Builder(this).setTitle("已办结待办已经过期")
                .setMessage("“" + event.title + "”已办结且超过设定时间，是否从茶茶日历中删除？")
                .setNegativeButton("保留", (dialog, which) -> promptNextExpiredCompletedTodo())
                .setPositiveButton("删除", (dialog, which) -> {
                    store.delete(event.id); prefs.edit().remove(expiredPromptKey(event.id)).apply(); refresh();
                    list.post(this::promptNextExpiredCompletedTodo);
                }).show();
    }

    /** 生成一条事项的过期询问记录键。 */
    private String expiredPromptKey(long eventId) { return "expired_todo_prompt_" + eventId; }

    /** 显示年份和月份双滚轮，确认后让年历和月历一起跳转。 */
    private void showYearMonthPicker() {
        // 当前年月来自正在浏览的年视图或月视图。
        YearMonth current = section == Section.YEAR ? YearMonth.of(yearCalendar.getYear(), calendar.getMonth().getMonthValue()) : calendar.getMonth();
        // 两个数字滚轮横向并排，左边选年份，右边选月份。
        LinearLayout wheels = new LinearLayout(this); wheels.setGravity(Gravity.CENTER); wheels.setPadding(dp(20), dp(8), dp(20), 0);
        NumberPicker year = new NumberPicker(this); year.setMinValue(1900); year.setMaxValue(2200); year.setValue(current.getYear()); year.setWrapSelectorWheel(false);
        NumberPicker month = new NumberPicker(this); month.setMinValue(1); month.setMaxValue(12); month.setValue(current.getMonthValue()); month.setWrapSelectorWheel(true);
        String[] months = new String[12]; for (int i=0;i<12;i++) months[i]=(i+1)+"月"; month.setDisplayedValues(months);
        wheels.addView(year, new LinearLayout.LayoutParams(0, dp(190), 1)); wheels.addView(month, new LinearLayout.LayoutParams(0, dp(190), 1));
        new AlertDialog.Builder(this).setTitle("跳转到年月").setView(wheels).setNegativeButton("取消", null).setPositiveButton("跳转", (d,w) -> {
            YearMonth target = YearMonth.of(year.getValue(), month.getValue()); yearCalendar.setYear(target.getYear()); calendar.setMonth(target); todosOnly=false; switchSection(Section.MONTH); updateTabStyles(); updateTitle();
        }).show();
    }

    /** 仅在首次安装且数据库为空时写入三条示例事项，帮助用户理解功能。 */
    private void seedSamplesIfEmpty() {
        if (!store.all().isEmpty() || prefs.getBoolean("seeded", false)) return;
        LocalDate now = LocalDate.now();
        store.save(new Event(0, "相识纪念日", now.plusDays(38), "纪念日", -1, Event.YEARLY, null, false, -1, false, 0, 0, false));
        store.save(new Event(0, "家人生日", now.plusDays(69), "生日", -1, Event.YEARLY, null, false, -1, false, 0, 0, false));
        store.save(new Event(0, "车辆年检", now.plusDays(96), "待办", 15, Event.NONE, LocalTime.of(9, 0), false, -1, false, 0, 0, false));
        prefs.edit().putBoolean("seeded", true).apply();
    }

    /**
     * 打开新增或编辑事项对话框。
     * 参数为空表示新增；不为空时把原事项内容填入表单，并在保存时更新同一主键。
     */
    private void showEventDialog(Event original) {
        // 使用单元素数组保存可变日期，便于日期选择回调更新它。
        // 从月历进入新增时默认使用上方选中的日期；编辑时仍使用事项原日期，之后都可手动调整。
        LocalDate defaultDate = section == Section.MONTH && calendar != null ? calendar.getSelectedDate() : LocalDate.now().plusDays(1);
        LocalDate[] chosen = { original == null ? defaultDate : original.date };
        // 农历字段与对应的公历落点同时保存，切换日期制时不会丢失同一天的换算结果。
        LunarDateUtils.LunarDate initialLunar = original != null && original.lunarBased
                ? new LunarDateUtils.LunarDate(LunarDateUtils.fromSolar(original.date).year, original.lunarMonth, original.lunarDay, original.lunarLeapMonth)
                : LunarDateUtils.fromSolar(chosen[0]);
        LunarDateUtils.LunarDate[] chosenLunar = {initialLunar};
        // 待办默认使用上午九点；只有类型为待办时才会显示和保存这个分钟精度时间。
        LocalTime[] chosenTime = { original != null && original.time != null ? original.time : LocalTime.of(9, 0) };
        // 表单纵向排列，各行统一使用“左标签、右控件”结构。
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(22), dp(8), dp(22), 0);
        // 名称是唯一必填的自由文本字段。
        EditText title = new EditText(this); title.setHint("名称，例如：结婚纪念日"); if (original != null) title.setText(original.title); box.addView(title, new LinearLayout.LayoutParams(-1, dp(56)));
        // 类型决定新事项的默认重复规则，但用户之后仍可手动修改。
        Spinner type = spinner(new String[]{"纪念日", "生日", "待办", "倒数日"});
        if (original != null) type.setSelection(original.type.equals("生日") ? 1 : original.type.equals("待办") ? 2 : original.type.equals("倒数日") ? 3 : 0);
        box.addView(labeled("类型", type));
        // 日期制逐条保存。公历与农历是平行设定，不受月历全局显示开关影响。
        Spinner dateSystem = spinner(new String[]{"公历", "农历"});
        dateSystem.setSelection(original != null && original.lunarBased ? 1 : 0);
        box.addView(labeled("日期类型", dateSystem));
        // 同一个日期按钮会根据日期制打开系统公历选择器或应用内农历滚轮。
        Button date = new Button(this); date.setAllCaps(false);
        date.setText(dateSystem.getSelectedItemPosition() == 1 ? formatLunarChoice(chosenLunar[0], chosen[0]) : formatDate(chosen[0]));
        date.setOnClickListener(v -> {
            if (dateSystem.getSelectedItemPosition() == 0) {
                new DatePickerDialog(this, (picker,y,m,d) -> {
                    chosen[0] = LocalDate.of(y,m+1,d); chosenLunar[0] = LunarDateUtils.fromSolar(chosen[0]); date.setText(formatDate(chosen[0]));
                }, chosen[0].getYear(), chosen[0].getMonthValue()-1, chosen[0].getDayOfMonth()).show();
            } else showLunarDatePicker(date, chosen, chosenLunar);
        });
        box.addView(labeled("日期", date));
        // 时间行只服务于待办。纪念日、生日和倒数日保持全天事项，不增加无意义输入。
        Button time = new Button(this); time.setAllCaps(false); time.setText(formatTime(chosenTime[0]));
        time.setOnClickListener(v -> new TimePickerDialog(this, (picker, hour, minute) -> {
            chosenTime[0] = LocalTime.of(hour, minute); time.setText(formatTime(chosenTime[0]));
        }, chosenTime[0].getHour(), chosenTime[0].getMinute(), true).show());
        LinearLayout timeRow = labeled("时间", time);
        timeRow.setVisibility(type.getSelectedItemPosition() == 2 ? View.VISIBLE : View.GONE);
        box.addView(timeRow);
        // 显示名称供用户选择，整数数组是数据库真正保存的提前天数。
        String[] visibilityNames = {"一直显示（默认）", "15天前显示", "7天前显示", "5天前显示", "3天前显示"};
        int[] visibilityValues = {-1, 15, 7, 5, 3};
        Spinner visibility = spinner(visibilityNames);
        if (original != null) for (int i=0;i<visibilityValues.length;i++) if (visibilityValues[i] == original.visibilityDays) visibility.setSelection(i);
        box.addView(labeled("下半区显示规则", visibility));
        // 中文重复名称与内部固定值按相同下标一一对应。
        String[] repeatNames = {"每年", "每月", "每周", "每日", "不重复"};
        String[] repeatValues = {Event.YEARLY, Event.MONTHLY, Event.WEEKLY, Event.DAILY, Event.NONE};
        Spinner repeat = spinner(repeatNames);
        if (original != null) for (int i=0;i<repeatValues.length;i++) if (repeatValues[i].equals(original.repeatRule)) repeat.setSelection(i);
        box.addView(labeled("重复", repeat));
        // 类型变化时即时控制时间行；新建事项还会按类型给出合理的默认重复规则。
        type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                timeRow.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
                if (original == null) repeat.setSelection(position <= 1 ? 0 : 4);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        dateSystem.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 1) {
                    chosenLunar[0] = LunarDateUtils.fromSolar(chosen[0]);
                    date.setText(formatLunarChoice(chosenLunar[0], chosen[0]));
                } else date.setText(formatDate(chosen[0]));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        // 先创建对话框，再在显示后接管保存按钮，以便校验失败时保持窗口不关闭。
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(original == null ? "新建日程" : "编辑日程").setView(box)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = title.getText().toString().trim();
            if (name.isEmpty()) { title.setError("请输入名称"); return; }
            long id = original == null ? 0 : original.id;
            String selectedType = type.getSelectedItem().toString();
            Event saved = new Event(id, name, chosen[0], selectedType,
                    visibilityValues[visibility.getSelectedItemPosition()], repeatValues[repeat.getSelectedItemPosition()],
                    "待办".equals(selectedType) ? chosenTime[0] : null,
                    original != null && original.completed && "待办".equals(selectedType),
                    original == null ? -1 : original.systemEventId,
                    dateSystem.getSelectedItemPosition() == 1,
                    chosenLunar[0].month, chosenLunar[0].day, chosenLunar[0].leapMonth);
            store.save(saved);
            prefs.edit().remove(expiredPromptKey(saved.id)).apply();
            syncOneToSystem(saved, true);
            dialog.dismiss(); refresh();
        }));
        dialog.show();
    }

    /** 长按事项后显示删除确认，避免误触造成数据丢失。 */
    private void confirmDelete(Event event) {
        new AlertDialog.Builder(this).setTitle("删除“" + event.title + "”？").setMessage("删除后无法恢复。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (d,w) -> {
                    long systemEventId = event.systemEventId;
                    store.delete(event.id);
                    prefs.edit().remove(expiredPromptKey(event.id)).apply();
                    new Thread(() -> SystemCalendarSync.delete(getApplicationContext(), systemEventId), "删除系统日历事项").start();
                    refresh();
                }).show();
    }

    /** 在三点按钮旁显示翻页、周数设置和天气说明菜单。 */
    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        if (section != Section.AGENDA) {
            popup.getMenu().add(section == Section.YEAR ? "上一年" : "上一月");
            popup.getMenu().add(section == Section.YEAR ? "下一年" : "下一月");
        }
        popup.getMenu().add("显示设置"); popup.getMenu().add("系统日历同步"); popup.getMenu().add("关于天气来源");
        popup.setOnMenuItemClickListener(item -> {
            String s = item.getTitle().toString();
            if (s.equals("上一月")) calendar.previousMonth();
            else if (s.equals("下一月")) calendar.nextMonth();
            else if (s.equals("上一年")) yearCalendar.setYear(yearCalendar.getYear()-1);
            else if (s.equals("下一年")) yearCalendar.setYear(yearCalendar.getYear()+1);
            else if (s.equals("显示设置")) showSettings();
            else if (s.equals("系统日历同步")) showCalendarSyncInfo();
            else showWeatherInfo();
            updateTitle(); return true;
        }); popup.show();
    }

    /** 显示周数、农历、小组件背景和字号设置，并把结果持久化。 */
    private void showSettings() {
        LinearLayout settings = new LinearLayout(this); settings.setOrientation(LinearLayout.VERTICAL); settings.setPadding(dp(18), dp(8), dp(18), 0);
        Switch weeks = new Switch(this); weeks.setText("在最左侧显示这是今年第几周"); weeks.setChecked(prefs.getBoolean("week_numbers", true)); weeks.setPadding(dp(6), dp(10), dp(6), dp(10));
        settings.addView(weeks, new LinearLayout.LayoutParams(-1, dp(58)));
        Switch lunar = new Switch(this); lunar.setText("在月历日期格中显示农历"); lunar.setChecked(prefs.getBoolean("show_lunar_dates", true)); lunar.setPadding(dp(6), dp(6), dp(6), dp(6));
        settings.addView(lunar, new LinearLayout.LayoutParams(-1, dp(54)));
        Switch festivals = new Switch(this); festivals.setText("显示传统节日和常用节日"); festivals.setChecked(prefs.getBoolean("show_festivals", true));
        Switch holidays = new Switch(this); holidays.setText("显示法定放假与补班（联网更新）"); holidays.setChecked(prefs.getBoolean("show_public_holidays", true));
        Switch terms = new Switch(this); terms.setText("显示二十四节气"); terms.setChecked(prefs.getBoolean("show_solar_terms", true));
        settings.addView(festivals, new LinearLayout.LayoutParams(-1, dp(50)));
        settings.addView(holidays, new LinearLayout.LayoutParams(-1, dp(50)));
        settings.addView(terms, new LinearLayout.LayoutParams(-1, dp(50)));
        Button updateHolidays = new Button(this); updateHolidays.setAllCaps(false); updateHolidays.setText("立即更新今年法定节假日");
        updateHolidays.setOnClickListener(v -> requestHolidayUpdate(LocalDate.now().getYear(), true, true));
        settings.addView(updateHolidays, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView holidayNote = text("联网更新只发送年份，不上传日程、待办或纪念日；失败时继续使用缓存和内置安排。", 12, Color.rgb(105,112,128), false);
        settings.addView(holidayNote, new LinearLayout.LayoutParams(-1, dp(48)));
        String[] transparencyNames = {"不透明", "透明 15%", "透明 30%", "透明 45%", "透明 60%"};
        int[] transparencyValues = {0, 15, 30, 45, 60};
        Spinner transparency = spinner(transparencyNames);
        int currentTransparency = prefs.getInt("widget_transparency", 15);
        for (int i = 0; i < transparencyValues.length; i++) if (transparencyValues[i] == currentTransparency) transparency.setSelection(i);
        settings.addView(labeled("小组件背景", transparency));
        String[] fontLevels = {"1级（当前默认）", "2级", "3级", "4级", "5级（全屏组件）"};
        Spinner headerFont = spinner(fontLevels); headerFont.setSelection(Math.max(0, Math.min(4, prefs.getInt("widget_header_font_level", 1) - 1)));
        Spinner eventFont = spinner(fontLevels); eventFont.setSelection(Math.max(0, Math.min(4, prefs.getInt("widget_event_font_level", 1) - 1)));
        settings.addView(labeled("顶部时间日期", headerFont));
        settings.addView(labeled("下方事项文字", eventFont));
        TextView powerNote = text("透明度仅在组件刷新时选择背景资源，不增加后台刷新频率。", 12, Color.rgb(105,112,128), false);
        settings.addView(powerNote, new LinearLayout.LayoutParams(-1, dp(42)));
        // 设置项较多时放进滚动容器，避免小屏手机底部选项和确认按钮被截断。
        ScrollView settingsScroll = new ScrollView(this); settingsScroll.addView(settings);
        new AlertDialog.Builder(this).setTitle("显示设置").setView(settingsScroll).setNegativeButton("取消", null).setPositiveButton("完成", (d,w) -> {
            prefs.edit().putBoolean("week_numbers", weeks.isChecked()).putBoolean("show_lunar_dates", lunar.isChecked())
                    .putBoolean("show_festivals", festivals.isChecked()).putBoolean("show_public_holidays", holidays.isChecked())
                    .putBoolean("show_solar_terms", terms.isChecked())
                    .putInt("widget_transparency", transparencyValues[transparency.getSelectedItemPosition()])
                    .putInt("widget_header_font_level", headerFont.getSelectedItemPosition() + 1)
                    .putInt("widget_event_font_level", eventFont.getSelectedItemPosition() + 1).apply();
            calendar.setShowWeekNumbers(weeks.isChecked()); calendar.setShowLunarDates(lunar.isChecked());
            refreshCalendarCulture(); if (holidays.isChecked()) requestHolidayUpdate(calendar.getMonth().getYear(), false, false); updateWidgets();
        }).show();
    }

    /**
     * 按三个独立开关生成当前六周网格的文化日期标签。
     * 同一天有多项时优先显示法定放假/补班，其次节气，最后显示节日。
     */
    private void refreshCalendarCulture() {
        if (calendar == null) return;
        boolean showFestivals = prefs.getBoolean("show_festivals", true);
        boolean showHolidays = prefs.getBoolean("show_public_holidays", true);
        boolean showTerms = prefs.getBoolean("show_solar_terms", true);
        HashMap<LocalDate, String> labels = new HashMap<>();
        LocalDate first = calendar.getMonth().atDay(1);
        LocalDate start = first.minusDays(first.getDayOfWeek().getValue() - 1L);
        HashMap<Integer, Map<LocalDate, HolidayRepository.HolidayInfo>> holidayYears = new HashMap<>();
        for (int index = 0; index < 42; index++) {
            LocalDate date = start.plusDays(index); String label = null;
            if (showFestivals) {
                List<String> names = CalendarCultureUtils.festivalNames(date);
                if (!names.isEmpty()) label = names.get(0);
            }
            if (showTerms) {
                String term = CalendarCultureUtils.solarTerm(date); if (!term.isEmpty()) label = term;
            }
            if (showHolidays) {
                Map<LocalDate, HolidayRepository.HolidayInfo> yearData = holidayYears.computeIfAbsent(date.getYear(), year -> HolidayRepository.year(this, year));
                HolidayRepository.HolidayInfo holiday = yearData.get(date); if (holiday != null) label = holiday.label();
            }
            if (label != null) labels.put(date, label.length() > 6 ? label.substring(0, 6) : label);
        }
        calendar.setCulturalLabels(labels);
    }

    /** 在后台按年更新法定节假日，成功后重绘月历；自动更新失败时保持静默和旧缓存。 */
    private void requestHolidayUpdate(int year, boolean force, boolean showResult) {
        if (!prefs.getBoolean("show_public_holidays", true) && !force) return;
        synchronized (holidayYearsLoading) {
            if (holidayYearsLoading.contains(year) || (!force && !HolidayRepository.needsRefresh(this, year))) return;
            holidayYearsLoading.add(year);
        }
        new Thread(() -> {
            boolean success = HolidayRepository.updateYear(getApplicationContext(), year);
            synchronized (holidayYearsLoading) { holidayYearsLoading.remove(year); }
            runOnUiThread(() -> {
                refreshCalendarCulture(); updateWidgets();
                if (showResult) Toast.makeText(this, success ? year + "年法定节假日已更新" : "更新失败，继续使用本地缓存或内置安排", Toast.LENGTH_LONG).show();
            });
        }, "更新法定节假日").start();
    }

    /** 展示系统日历同步状态，并允许用户重新授权或立即补同步。 */
    private void showCalendarSyncInfo() {
        boolean granted = SystemCalendarSync.hasPermission(this);
        String message = granted
                ? "系统日历权限已开启。茶茶日历会把事项与提醒写入可写系统日历，通知样式由手机日历的通知渠道决定。"
                : "尚未获得系统日历权限。授权后可借用系统日历提供声音、震动、横幅和状态栏提醒；拒绝不会影响本地功能。";
        new AlertDialog.Builder(this).setTitle("系统日历同步").setMessage(message).setNegativeButton("取消", null)
                .setPositiveButton(granted ? "立即同步" : "去授权", (dialog, which) -> {
                    if (granted) syncAllToSystem(); else requestCalendarPermission();
                }).show();
    }

    /** 首次进入新版时先解释用途，再请求系统日历读写权限。 */
    private void requestCalendarPermissionIfNeeded() {
        if (SystemCalendarSync.hasPermission(this) || prefs.getBoolean("calendar_permission_asked", false)) return;
        new AlertDialog.Builder(this).setTitle("启用系统日历提醒")
                .setMessage("授权后，茶茶日历新增或修改的事项会同步写入手机系统日历，并由系统日历负责声音、震动、横幅和状态栏通知。拒绝后仍可正常使用本地日历。")
                .setNegativeButton("暂不启用", null).setPositiveButton("继续", (dialog, which) -> requestCalendarPermission()).show();
    }

    /** 调用安卓运行时权限界面申请日历读取和写入权限。 */
    private void requestCalendarPermission() {
        prefs.edit().putBoolean("calendar_permission_asked", true).apply();
        requestPermissions(new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR},
                SystemCalendarSync.PERMISSION_REQUEST);
    }

    /** 在后台把全部本地事项补同步到系统日历，避免数据库操作阻塞界面动画。 */
    private void syncAllToSystem() {
        if (!SystemCalendarSync.hasPermission(this)) { requestCalendarPermission(); return; }
        Toast.makeText(this, "正在同步到系统日历…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int synced = 0;
            for (Event event : store.all()) {
                long systemId = SystemCalendarSync.sync(getApplicationContext(), event);
                store.setSystemEventId(event.id, systemId);
                if (systemId > 0) synced++;
            }
            int finalSynced = synced;
            runOnUiThread(() -> Toast.makeText(this,
                    finalSynced > 0 ? "已同步 " + finalSynced + " 条事项到系统日历" : "没有找到可写的系统日历，请先在系统日历中启用一个账户",
                    Toast.LENGTH_LONG).show());
        }, "补同步系统日历").start();
    }

    /** 在后台同步单条新增、编辑或恢复的事项，并保存系统返回的事件编号。 */
    private void syncOneToSystem(Event event, boolean showFailure) {
        if (!SystemCalendarSync.hasPermission(this)) {
            if (showFailure) Toast.makeText(this, "事项已保存在本地；授权系统日历后才能同步提醒。", Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> {
            long systemId = SystemCalendarSync.sync(getApplicationContext(), event);
            event.systemEventId = systemId;
            store.setSystemEventId(event.id, systemId);
            if (showFailure && systemId < 0) runOnUiThread(() -> Toast.makeText(this,
                    "事项已保存在本地，但没有找到可写的系统日历。", Toast.LENGTH_LONG).show());
        }, "同步单条系统日历事项").start();
    }

    /** 说明系统天气接口限制，并报告当前是否找到可打开的天气应用。 */
    private void showWeatherInfo() {
        String status = SystemWeatherBridge.findWeatherApp(this) == null ? "没有发现可打开的系统天气应用。" : "已发现系统天气应用，小组件会提供直达入口。";
        new AlertDialog.Builder(this).setTitle("天气来源").setMessage(status + "\n\nAndroid 没有统一的系统天气读取接口。本应用不会用高权限读取厂商私有数据；日历和时钟始终离线可用。").setPositiveButton("知道了", null).show();
    }

    /** 创建左侧标签、右侧输入控件的等高表单行，保证视觉对齐。 */
    private LinearLayout labeled(String label, View control) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setBaselineAligned(false);
        TextView name = text(label, 13, Color.rgb(96,103,120), false); name.setGravity(Gravity.CENTER_VERTICAL); row.addView(name, new LinearLayout.LayoutParams(dp(118), dp(54)));
        row.addView(control, new LinearLayout.LayoutParams(0, dp(54), 1)); return row;
    }

    /** 创建带下拉选项的选择控件。 */
    private Spinner spinner(String[] values) { Spinner s = new Spinner(this); s.setGravity(Gravity.CENTER_VERTICAL); s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values)); return s; }
    /** 创建工具栏使用的透明小按钮。 */
    private Button smallButton(String label) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextColor(Color.rgb(60,68,86)); b.setBackgroundColor(Color.TRANSPARENT); b.setMinWidth(0); return b; }
    /** 创建文字控件，并一次性设置内容、字号、颜色和字重。 */
    private TextView text(String value, float size, int color, boolean bold) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t; }
    /** 把与屏幕密度无关的尺寸转换为实际像素。 */
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    /** 把日期转换为完整中文年月日。 */
    private String formatDate(LocalDate d) { return d.getYear() + "年" + d.getMonthValue() + "月" + d.getDayOfMonth() + "日"; }
    /** 同时显示农历设定和本次对应的公历落点，帮助用户在保存前核对日期。 */
    private String formatLunarChoice(LunarDateUtils.LunarDate lunar, LocalDate solar) {
        return LunarDateUtils.format(lunar.month, lunar.day, lunar.leapMonth) + "（" + formatDate(solar) + "）";
    }

    /** 打开农历年、月、日滚轮；闰月由选中年份的民用农历规则自动加入月份列表。 */
    private void showLunarDatePicker(Button dateButton, LocalDate[] chosenSolar, LunarDateUtils.LunarDate[] chosenLunar) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18), dp(4), dp(18), 0);
        LinearLayout wheels = new LinearLayout(this); wheels.setGravity(Gravity.CENTER);
        NumberPicker year = new NumberPicker(this); year.setMinValue(1901); year.setMaxValue(2100); year.setWrapSelectorWheel(false); year.setValue(Math.max(1901, Math.min(2100, chosenLunar[0].year)));
        NumberPicker month = new NumberPicker(this);
        NumberPicker day = new NumberPicker(this); day.setMinValue(1); day.setMaxValue(30); day.setValue(chosenLunar[0].day);
        // 月份数组使用负数代表闰月，例如负四表示“闰四月”；普通年份只有十二项。
        int[][] monthOptions = {lunarMonthOptions(year.getValue())};
        int preferredMonth = chosenLunar[0].leapMonth ? -chosenLunar[0].month : chosenLunar[0].month;
        configureLunarMonthPicker(month, monthOptions[0], preferredMonth);
        String[] days = new String[30]; for (int i=0;i<30;i++) days[i]=LunarDateUtils.dayName(i+1); day.setDisplayedValues(days);
        wheels.addView(year, new LinearLayout.LayoutParams(0, dp(180), 1)); wheels.addView(month, new LinearLayout.LayoutParams(0, dp(180), 1)); wheels.addView(day, new LinearLayout.LayoutParams(0, dp(180), 1));
        TextView leapInfo = text("", 12, Color.rgb(96,103,120), false); leapInfo.setGravity(Gravity.CENTER);
        updateLunarLeapInfo(leapInfo, year.getValue());
        year.setOnValueChangedListener((picker, oldYear, newYear) -> {
            int oldSelected = monthOptions[0][month.getValue()];
            monthOptions[0] = lunarMonthOptions(newYear);
            configureLunarMonthPicker(month, monthOptions[0], oldSelected);
            updateLunarLeapInfo(leapInfo, newYear);
        });
        box.addView(wheels); box.addView(leapInfo, new LinearLayout.LayoutParams(-1, dp(44)));
        new AlertDialog.Builder(this).setTitle("选择农历日期").setView(box).setNegativeButton("取消", null).setPositiveButton("确定", (dialog, which) -> {
            int signedMonth = monthOptions[0][month.getValue()]; boolean leapMonth = signedMonth < 0; int monthNumber = Math.abs(signedMonth);
            LocalDate solar = LunarDateUtils.toSolar(year.getValue(), monthNumber, day.getValue(), leapMonth);
            if (solar == null) { Toast.makeText(this, "这个农历月没有所选日号，请重新选择。", Toast.LENGTH_LONG).show(); return; }
            chosenSolar[0] = solar; chosenLunar[0] = new LunarDateUtils.LunarDate(year.getValue(), monthNumber, day.getValue(), leapMonth);
            dateButton.setText(formatLunarChoice(chosenLunar[0], chosenSolar[0]));
        }).show();
    }

    /** 生成指定农历年的月份顺序，并在正确位置自动插入负数表示的闰月。 */
    private int[] lunarMonthOptions(int lunarYear) {
        int leap = LunarDateUtils.leapMonthOfYear(lunarYear); int[] values = new int[leap == 0 ? 12 : 13]; int index = 0;
        for (int value = 1; value <= 12; value++) { values[index++] = value; if (value == leap) values[index++] = -value; }
        return values;
    }

    /** 把月份数组应用到滚轮；原来选择的闰月在新年份不存在时自动回到同名普通月份。 */
    private void configureLunarMonthPicker(NumberPicker picker, int[] values, int preferredMonth) {
        String[] labels = new String[values.length]; int selectedIndex = 0;
        for (int index = 0; index < values.length; index++) {
            int value = values[index]; labels[index] = (value < 0 ? "闰" : "") + LunarDateUtils.monthName(Math.abs(value));
            if (value == preferredMonth || (preferredMonth < 0 && value == -preferredMonth)) selectedIndex = index;
        }
        picker.setDisplayedValues(null); picker.setMinValue(0); picker.setMaxValue(values.length - 1); picker.setDisplayedValues(labels); picker.setValue(selectedIndex); picker.setWrapSelectorWheel(true);
    }

    /** 在滚轮下方说明系统自动识别出的闰月，避免用户把公历闰年和农历闰月混淆。 */
    private void updateLunarLeapInfo(TextView target, int lunarYear) {
        int leap = LunarDateUtils.leapMonthOfYear(lunarYear);
        target.setText(leap == 0 ? lunarYear + "农历年没有闰月" : lunarYear + "农历年自动识别：闰" + LunarDateUtils.monthName(leap));
    }
    /** 把待办时间统一格式化为二十四小时制的时分。 */
    private String formatTime(LocalTime time) { return String.format(Locale.CHINA, "%02d:%02d", time.getHour(), time.getMinute()); }
    /** 用户修改数据后主动刷新全部桌面小组件。 */
    private void updateWidgets() { AppWidgetManager m = AppWidgetManager.getInstance(this); int[] ids = m.getAppWidgetIds(new ComponentName(this, CalendarWidgetProvider.class)); CalendarWidgetProvider.updateAll(this, m, ids); }

    /**
     * 下半区事项列表适配器。
     * 每一行左侧显示名称、重复规则、日期、类型和提前显示规则，右侧显示倒计时。
     */
    private static final class EventAdapter extends ArrayAdapter<Event> {
        /** 待办勾选状态变化时通知主页面更新数据库和系统日历。 */
        interface CompletionListener { void onChanged(Event event, boolean completed); }
        /** 创建文字和尺寸时需要的页面对象。 */
        private final Activity activity;
        /** 把勾选事件传回主页面的回调。 */
        private final CompletionListener completionListener;
        /** 当前列表计算倒计时所使用的基准日期。 */
        private LocalDate anchor = LocalDate.now();
        /** 为真时显示待办选择框；普通日程和月历下半区不显示。 */
        private boolean todoMode;
        /** 创建适配器并交给父类管理初始数据。 */
        EventAdapter(Activity a, List<Event> e, CompletionListener listener) {
            super(a, android.R.layout.simple_list_item_1, e); activity = a; completionListener = listener;
        }
        /** 切换待办专用行样式，并要求列表立即重绘。 */
        void setTodoMode(boolean enabled) { todoMode = enabled; notifyDataSetChanged(); }
        /** 更换全部列表数据，同时更新倒计时基准日期。 */
        void replace(List<Event> items, LocalDate value) { anchor = value; clear(); addAll(items); notifyDataSetChanged(); }
        /** 获取或复用一张事项卡片，并把当前位置的数据填入左右文字区域。 */
        @Override public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row = convert instanceof LinearLayout ? (LinearLayout) convert : createRow();
            Event e = getItem(position); LocalDate next = e.nextDate(anchor); long days = e.daysUntil(anchor);
            CheckBox completed = (CheckBox) row.getChildAt(0);
            TextView title = (TextView) row.getChildAt(1); TextView countdown = (TextView) row.getChildAt(2);
            completed.setOnCheckedChangeListener(null);
            completed.setVisibility(todoMode && e.isTodo() ? View.VISIBLE : View.GONE);
            completed.setChecked(e.completed);
            completed.setOnCheckedChangeListener((button, checked) -> completionListener.onChanged(e, checked));
            String timePart = e.timeLabel().isEmpty() ? "" : "  " + e.timeLabel();
            boolean overdue = e.isTodo() && !e.completed && e.isOverdue();
            String overduePart = overdue ? "  ·  已过期" : "";
            title.setText(String.format(Locale.CHINA, "%s  ·  %s%s\n%s%s  ·  %s  ·  %s",
                    e.title, e.repeatLabel(), overduePart, e.displayDate(next), timePart, e.type, e.visibilityLabel()));
            countdown.setText(overdue ? "已过期" : days == 0 ? (e.timeLabel().isEmpty() ? "今天" : e.timeLabel()) : days > 0 ? days + " 天后" : "已过 " + (-days) + " 天");
            int mainColor = e.completed ? Color.rgb(145,148,156) : overdue ? Color.rgb(210,55,67) : Color.rgb(25,28,35);
            title.setTextColor(mainColor); countdown.setTextColor(e.completed ? mainColor : overdue ? Color.rgb(210,55,67) : Color.rgb(73,91,174));
            row.setAlpha(e.completed ? .68f : 1f);
            GradientDrawable background = new GradientDrawable(); background.setColor(e.completed ? Color.rgb(237,238,241) : Color.WHITE);
            background.setCornerRadius(dp(16)); background.setStroke(dp(1), Color.rgb(225,227,234)); row.setBackground(background);
            return row;
        }
        /** 创建一张新的白色圆角事项卡片。后续滚动时会复用它以降低内存开销。 */
        private LinearLayout createRow() {
            LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(16), dp(8), dp(16), dp(8));
            GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(16)); bg.setStroke(dp(1), Color.rgb(233,235,242)); row.setBackground(bg);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(82)); rp.setMargins(0, dp(4), 0, dp(4)); row.setLayoutParams(rp);
            CheckBox box = new CheckBox(activity); box.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.rgb(82,110,240)));
            // 不抢占列表行焦点：点选择框正常勾选，点行内其他位置仍触发编辑，长按仍触发删除。
            box.setFocusable(false); box.setFocusableInTouchMode(false); box.setClickable(true);
            row.addView(box, new LinearLayout.LayoutParams(dp(42), -1));
            TextView t = new TextView(activity); t.setTextSize(16); t.setTextColor(Color.rgb(25,28,35)); t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); t.setGravity(Gravity.CENTER_VERTICAL); t.setLineSpacing(0,1.15f);
            row.addView(t, new LinearLayout.LayoutParams(0,-1,1));
            TextView c = new TextView(activity); c.setTextSize(18); c.setTextColor(Color.rgb(73,91,174)); c.setTypeface(Typeface.DEFAULT,Typeface.BOLD); c.setGravity(Gravity.CENTER);
            row.addView(c, new LinearLayout.LayoutParams(dp(86),-1)); return row;
        }
        /** 把与屏幕密度无关的尺寸转换为实际像素。 */
        private int dp(float v) { return Math.round(v * activity.getResources().getDisplayMetrics().density); }
    }
}
