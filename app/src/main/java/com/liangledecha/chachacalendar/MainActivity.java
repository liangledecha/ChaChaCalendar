package com.liangledecha.chachacalendar;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.NumberPicker;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
                calendar.setMonth(selectedMonth); calendar.setEvents(store.all()); switchSection(Section.MONTH);
            }
        });
        page.addView(yearCalendar, new LinearLayout.LayoutParams(-1, 0, 1));

        // 第五步：加入三态月历，并把日期、状态和月份变化回调连接到页面刷新逻辑。
        calendar = new MonthCalendarView(this);
        calendar.setShowWeekNumbers(prefs.getBoolean("week_numbers", true));
        calendar.setListener(new MonthCalendarView.Listener() {
            @Override public void onDateSelected(LocalDate date) { if (section == Section.MONTH) updateMonthList(); updateTitle(); }
            @Override public void onModeChanged(MonthCalendarView.Mode mode) {
                lowerPanel.setVisibility(mode == MonthCalendarView.Mode.EXPANDED ? View.GONE : View.VISIBLE);
            }
            @Override public void onPeriodChanged() { calendar.setEvents(store.all()); if (section == Section.MONTH) updateMonthList(); updateTitle(); }
        });
        page.addView(calendar, new LinearLayout.LayoutParams(-1, -2));

        // 第六步：创建下半区摘要和可滚动事项列表。
        lowerPanel = new LinearLayout(this); lowerPanel.setOrientation(LinearLayout.VERTICAL);
        lowerPanel.setPadding(dp(2), 0, dp(2), 0);
        summary = text("", 15, Color.rgb(38,45,62), true); summary.setGravity(Gravity.CENTER_VERTICAL);
        lowerPanel.addView(summary, new LinearLayout.LayoutParams(-1, dp(42)));
        list = new ListView(this); list.setDividerHeight(0); list.setSelector(android.R.color.transparent);
        adapter = new EventAdapter(this, new ArrayList<>()); list.setAdapter(adapter);
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
        if (section == Section.YEAR) monthTitle.setText(yearCalendar.getYear() + "年  ▾");
        else if (section == Section.AGENDA) monthTitle.setText(todosOnly ? "待办" : "日程");
        else monthTitle.setText(calendar.getMonth().getYear() + "年" + calendar.getMonth().getMonthValue() + "月  ▾");
    }

    /** 在新增、修改或删除事项后重新读取数据库并刷新所有相关界面。 */
    private void refresh() {
        List<Event> all = store.all();
        calendar.setEvents(all);
        if (section == Section.AGENDA) replaceForAgenda(); else updateMonthList();
        updateSummary();
        updateTitle();
        updateWidgets();
    }

    /** 更新列表上方的日期或当前列表类型说明。 */
    private void updateSummary() {
        LocalDate now = LocalDate.now();
        summary.setText(section == Section.AGENDA ? (todosOnly ? "待办  ·  按下一次发生日期排列" : "全部日程  ·  按下一次发生日期排列") : "今天  " + now.getMonthValue() + "月" + now.getDayOfMonth() + "日");
    }

    /** 以月历选中日期为基准，严格按提前显示天数生成下半区列表。 */
    private void updateMonthList() {
        if (calendar == null || adapter == null) return;
        LocalDate anchor = calendar.getSelectedDate();
        adapter.replace(store.visible(anchor, false), anchor); updateSummary();
    }

    /** 为日程页或待办页装载相应数据，列表倒计时统一以今天计算。 */
    private void replaceForAgenda() { adapter.replace(todosOnly ? todoEvents() : store.all(), LocalDate.now()); }

    /** 从全部事项中筛出类型为“待办”的记录。 */
    private List<Event> todoEvents() {
        ArrayList<Event> result = new ArrayList<>(); for (Event e : store.all()) if ("待办".equals(e.type)) result.add(e); return result;
    }

    /** 打开待办专用列表并刷新底部选中状态。 */
    private void showTodoPage() { todosOnly = true; switchSection(Section.AGENDA); updateTabStyles(); }

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
            YearMonth target = YearMonth.of(year.getValue(), month.getValue()); yearCalendar.setYear(target.getYear()); calendar.setMonth(target); calendar.setEvents(store.all()); todosOnly=false; switchSection(Section.MONTH); updateTabStyles(); updateTitle();
        }).show();
    }

    /** 仅在首次安装且数据库为空时写入三条示例事项，帮助用户理解功能。 */
    private void seedSamplesIfEmpty() {
        if (!store.all().isEmpty() || prefs.getBoolean("seeded", false)) return;
        LocalDate now = LocalDate.now();
        store.save(new Event(0, "相识纪念日", now.plusDays(38), "纪念日", -1, Event.YEARLY));
        store.save(new Event(0, "家人生日", now.plusDays(69), "生日", -1, Event.YEARLY));
        store.save(new Event(0, "车辆年检", now.plusDays(96), "待办", 15, Event.NONE));
        prefs.edit().putBoolean("seeded", true).apply();
    }

    /**
     * 打开新增或编辑事项对话框。
     * 参数为空表示新增；不为空时把原事项内容填入表单，并在保存时更新同一主键。
     */
    private void showEventDialog(Event original) {
        // 使用单元素数组保存可变日期，便于日期选择回调更新它。
        LocalDate[] chosen = { original == null ? LocalDate.now().plusDays(1) : original.date };
        // 表单纵向排列，各行统一使用“左标签、右控件”结构。
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(22), dp(8), dp(22), 0);
        // 名称是唯一必填的自由文本字段。
        EditText title = new EditText(this); title.setHint("名称，例如：结婚纪念日"); if (original != null) title.setText(original.title); box.addView(title, new LinearLayout.LayoutParams(-1, dp(56)));
        // 类型决定新事项的默认重复规则，但用户之后仍可手动修改。
        Spinner type = spinner(new String[]{"纪念日", "生日", "待办", "倒数日"});
        if (original != null) type.setSelection(original.type.equals("生日") ? 1 : original.type.equals("待办") ? 2 : original.type.equals("倒数日") ? 3 : 0);
        box.addView(labeled("类型", type));
        // 日期按钮打开系统日期选择器，并把选择结果写回按钮文字。
        Button date = new Button(this); date.setAllCaps(false); date.setText(formatDate(chosen[0]));
        date.setOnClickListener(v -> new DatePickerDialog(this, (picker,y,m,d) -> { chosen[0] = LocalDate.of(y,m+1,d); date.setText(formatDate(chosen[0])); }, chosen[0].getYear(), chosen[0].getMonthValue()-1, chosen[0].getDayOfMonth()).show());
        box.addView(labeled("日期", date));
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
        else type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { repeat.setSelection(position <= 1 ? 0 : 4); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        box.addView(labeled("重复", repeat));

        // 先创建对话框，再在显示后接管保存按钮，以便校验失败时保持窗口不关闭。
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(original == null ? "新建日程" : "编辑日程").setView(box)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = title.getText().toString().trim();
            if (name.isEmpty()) { title.setError("请输入名称"); return; }
            long id = original == null ? 0 : original.id;
            store.save(new Event(id, name, chosen[0], type.getSelectedItem().toString(), visibilityValues[visibility.getSelectedItemPosition()], repeatValues[repeat.getSelectedItemPosition()]));
            dialog.dismiss(); refresh();
        }));
        dialog.show();
    }

    /** 长按事项后显示删除确认，避免误触造成数据丢失。 */
    private void confirmDelete(Event event) {
        new AlertDialog.Builder(this).setTitle("删除“" + event.title + "”？").setMessage("删除后无法恢复。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (d,w) -> { store.delete(event.id); refresh(); }).show();
    }

    /** 在三点按钮旁显示翻页、周数设置和天气说明菜单。 */
    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        if (section != Section.AGENDA) {
            popup.getMenu().add(section == Section.YEAR ? "上一年" : "上一月");
            popup.getMenu().add(section == Section.YEAR ? "下一年" : "下一月");
        }
        popup.getMenu().add("显示设置"); popup.getMenu().add("关于天气来源");
        popup.setOnMenuItemClickListener(item -> {
            String s = item.getTitle().toString();
            if (s.equals("上一月")) calendar.previousMonth();
            else if (s.equals("下一月")) calendar.nextMonth();
            else if (s.equals("上一年")) yearCalendar.setYear(yearCalendar.getYear()-1);
            else if (s.equals("下一年")) yearCalendar.setYear(yearCalendar.getYear()+1);
            else if (s.equals("显示设置")) showSettings();
            else showWeatherInfo();
            updateTitle(); return true;
        }); popup.show();
    }

    /** 显示年周数开关，并把结果持久化到设置存储。 */
    private void showSettings() {
        Switch weeks = new Switch(this); weeks.setText("在最左侧显示这是今年第几周"); weeks.setChecked(prefs.getBoolean("week_numbers", true)); weeks.setPadding(dp(24), dp(16), dp(24), dp(16));
        new AlertDialog.Builder(this).setTitle("显示设置").setView(weeks).setNegativeButton("取消", null).setPositiveButton("完成", (d,w) -> {
            prefs.edit().putBoolean("week_numbers", weeks.isChecked()).apply(); calendar.setShowWeekNumbers(weeks.isChecked());
        }).show();
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
    /** 用户修改数据后主动刷新全部桌面小组件。 */
    private void updateWidgets() { AppWidgetManager m = AppWidgetManager.getInstance(this); int[] ids = m.getAppWidgetIds(new ComponentName(this, CalendarWidgetProvider.class)); CalendarWidgetProvider.updateAll(this, m, ids); }

    /**
     * 下半区事项列表适配器。
     * 每一行左侧显示名称、重复规则、日期、类型和提前显示规则，右侧显示倒计时。
     */
    private static final class EventAdapter extends ArrayAdapter<Event> {
        /** 创建文字和尺寸时需要的页面对象。 */
        private final Activity activity;
        /** 当前列表计算倒计时所使用的基准日期。 */
        private LocalDate anchor = LocalDate.now();
        /** 创建适配器并交给父类管理初始数据。 */
        EventAdapter(Activity a, List<Event> e) { super(a, android.R.layout.simple_list_item_1, e); activity = a; }
        /** 更换全部列表数据，同时更新倒计时基准日期。 */
        void replace(List<Event> items, LocalDate value) { anchor = value; clear(); addAll(items); notifyDataSetChanged(); }
        /** 获取或复用一张事项卡片，并把当前位置的数据填入左右文字区域。 */
        @Override public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row = convert instanceof LinearLayout ? (LinearLayout) convert : createRow();
            Event e = getItem(position); LocalDate next = e.nextDate(anchor); long days = e.daysUntil(anchor);
            TextView title = (TextView) row.getChildAt(0); TextView countdown = (TextView) row.getChildAt(1);
            title.setText(e.title + "  ·  " + e.repeatLabel() + "\n" + next.getYear() + "年" + next.getMonthValue() + "月" + next.getDayOfMonth() + "日  ·  " + e.type + "  ·  " + e.visibilityLabel());
            countdown.setText(days == 0 ? "今天" : days > 0 ? days + " 天后" : "已过 " + (-days) + " 天"); return row;
        }
        /** 创建一张新的白色圆角事项卡片。后续滚动时会复用它以降低内存开销。 */
        private LinearLayout createRow() {
            LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(16), dp(8), dp(16), dp(8));
            GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(16)); bg.setStroke(dp(1), Color.rgb(233,235,242)); row.setBackground(bg);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(82)); rp.setMargins(0, dp(4), 0, dp(4)); row.setLayoutParams(rp);
            TextView t = new TextView(activity); t.setTextSize(16); t.setTextColor(Color.rgb(32,39,55)); t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); t.setGravity(Gravity.CENTER_VERTICAL); t.setLineSpacing(0,1.15f);
            row.addView(t, new LinearLayout.LayoutParams(0,-1,1));
            TextView c = new TextView(activity); c.setTextSize(18); c.setTextColor(Color.rgb(73,91,174)); c.setTypeface(Typeface.DEFAULT,Typeface.BOLD); c.setGravity(Gravity.CENTER);
            row.addView(c, new LinearLayout.LayoutParams(dp(86),-1)); return row;
        }
        /** 把与屏幕密度无关的尺寸转换为实际像素。 */
        private int dp(float v) { return Math.round(v * activity.getResources().getDisplayMetrics().density); }
    }
}
