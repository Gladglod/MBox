package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.view.View;
import androidx.annotation.NonNull;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.ui.adapter.BackupAdapter;
import com.github.tvbox.osc.util.FileUtils;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.tvbox.osc.util.GlassTip;
public class BackupDialog extends BaseDialog {

    public BackupDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_backup);
        TvRecyclerView tvRecyclerView = ((TvRecyclerView) findViewById(R.id.list));
        BackupAdapter adapter = new BackupAdapter();
        tvRecyclerView.setAdapter(adapter);
        adapter.setNewData(allBackup());
        adapter.setOnItemChildClickListener(new BaseQuickAdapter.OnItemChildClickListener() {
            @Override
            public void onItemChildClick(BaseQuickAdapter adapter, View view, int position) {
                if (view.getId() == R.id.tvName) {
                    restore((String) adapter.getItem(position));
                }
                else if (view.getId() == R.id.tvDel) {
                    delete((String) adapter.getItem(position));
                    adapter.setNewData(allBackup());
                }
            }
        });
        findViewById(R.id.backupNow).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                backup();
                adapter.setNewData(allBackup());
            }
        });
        findViewById(R.id.storagePermission).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (XXPermissions.isGranted(getContext(), Permission.Group.STORAGE)) {
                    GlassTip.show(getContext(), "已获得存储权限");
                } else {
                    XXPermissions.with(getContext())
                            .permission(Permission.Group.STORAGE)
                            .request(new OnPermissionCallback() {
                                @Override
                                public void onGranted(List<String> permissions, boolean all) {
                                    if (all) {
                                        adapter.setNewData(allBackup());
                                        GlassTip.show(getContext(), "已获得存储权限");
                                    }
                                }

                                @Override
                                public void onDenied(List<String> permissions, boolean never) {
                                    if (never) {
                                        GlassTip.show(getContext(), "获取存储权限失败,请在系统设置中开启");
                                        XXPermissions.startPermissionActivity((Activity) getContext(), permissions);
                                    } else {
                                        GlassTip.show(getContext(), "获取存储权限失败");
                                    }
                                }
                            });
                }
            }
        });
    }

    List<String> allBackup() {
        ArrayList<String> result = new ArrayList<>();
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File file = new File(root + "/tvbox_backup/");
            if (!file.exists()) {
                return result;
            }
            File[] list = file.listFiles();
            if (list == null) {
                return result;
            }
            Arrays.sort(list, new Comparator<File>() {
                @Override
                public int compare(File o1, File o2) {
                    if (o1.isDirectory() && o2.isFile()) return -1;
                    return o1.isFile() && o2.isDirectory() ? 1 : o2.getName().compareTo(o1.getName());
                }
            });
            for (File f : list) {
                if (result.size() > 10) {
                    FileUtils.recursiveDelete(f);
                    continue;
                }
                if (f.isDirectory()) {
                    result.add(f.getName());
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return result;
    }

    void restore(String dir) {
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File backup = new File(root + "/tvbox_backup/" + dir);
            if (backup.exists()) {
                File db = new File(backup, "sqlite");
                if (AppDataManager.restore(db)) {
                    boolean hawkOk = false;
                    byte[] data = FileUtils.readSimple(new File(backup, "hawk"));
                    if (data != null) {
                        try {
                            String hawkJson = new String(data, "UTF-8");
                            JSONObject jsonObject = new JSONObject(hawkJson);
                            Iterator<String> it = jsonObject.keys();
                            SharedPreferences.Editor editor =
                                    App.getInstance().getSharedPreferences("Hawk2", Context.MODE_PRIVATE).edit();
                            SharedPreferences.Editor cryptoEditor =
                                    App.getInstance().getSharedPreferences("crypto.KEY_256", Context.MODE_PRIVATE).edit();
                            while (it.hasNext()) {
                                String key = it.next();
                                String value = jsonObject.optString(key, null);
                                if (value == null) continue;
                                if (key.equals("cipher_key")) {
                                    cryptoEditor.putString(key, value);
                                } else {
                                    restorePrefValue(editor, key, value);
                                }
                            }
                            editor.commit();
                            cryptoEditor.commit();
                            hawkOk = true;
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                    if (hawkOk) {
                        GlassTip.show(getContext(), "恢复成功,即将自动重启应用!");
                    } else {
                        // Hawk 文件读取失败不能就此停止：DB 已被替换，不重启会继续用
                        // 内存里的旧连接 / 旧配置，反而造成"数据错乱、像被丢掉"的体验
                        GlassTip.show(getContext(), "Hawk恢复失败,但DB已恢复,即将自动重启应用!");
                    }
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            restartApp();
                        }
                    }, 3000);
                } else {
                    GlassTip.show(getContext(), "DB文件恢复失败!");
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * 恢复单条 Hawk 配置：新备份带类型标签（{@link #TYPE_TAG} 前缀）按原生类型恢复，
     * 旧备份（无标签）按字符串原样恢复，保持向后兼容。
     * Hawk 的密文是 Base64，不可能以 \u0001 开头，因此不会误判。
     */
    private static void restorePrefValue(SharedPreferences.Editor editor, String key, String rawValue) {
        try {
            if (rawValue == null || !rawValue.startsWith(TYPE_TAG)) {
                editor.putString(key, rawValue);
                return;
            }
            String payload = rawValue.substring(TYPE_TAG.length());
            char tag = payload.isEmpty() ? '?' : payload.charAt(0);
            String value = payload.length() > 2 ? payload.substring(2) : "";
            switch (tag) {
                case 'i':
                    editor.putInt(key, Integer.parseInt(value));
                    break;
                case 'b':
                    editor.putBoolean(key, Boolean.parseBoolean(value));
                    break;
                case 'f':
                    editor.putFloat(key, Float.parseFloat(value));
                    break;
                case 'l':
                    editor.putLong(key, Long.parseLong(value));
                    break;
                case 's':
                    editor.putString(key, value);
                    break;
                case 'S': {
                    JSONArray arr = new JSONArray(value);
                    Set<String> set = new HashSet<>();
                    for (int i = 0; i < arr.length(); i++) {
                        set.add(arr.optString(i));
                    }
                    editor.putStringSet(key, set);
                    break;
                }
                default:
                    // 未知标签：按完整原始字符串恢复，尽量不丢内容
                    editor.putString(key, rawValue);
                    break;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }
    private void restartApp() {
        Context context = getContext();
        if (context != null) {
            Intent i = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(i);
                System.exit(0);
            }
        }
    }
    void backup() {
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File file = new File(root + "/tvbox_backup/");
            if (!file.exists())
                file.mkdirs();
            Date now = new Date();
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd-HHmmss");
            File backup = new File(file, f.format(now));
            backup.mkdirs();
            File db = new File(backup, "sqlite");
            if (AppDataManager.backup(db)) {
                SharedPreferences sharedPreferences = App.getInstance().getSharedPreferences("Hawk2", Context.MODE_PRIVATE);
                JSONObject jsonObject = new JSONObject();
                for (Map.Entry<String, ?> entry : sharedPreferences.getAll().entrySet()) {
                    // 带类型标签编码：非 String 值（Integer/Boolean/long/float/Set）在恢复时还原原生类型，
                    // 否则历史条数上限等配置恢复后会损坏，进而触发历史记录被裁剪丢失
                    jsonObject.put(entry.getKey(), encodePrefValue(entry.getValue()));
                }
                sharedPreferences = App.getInstance().getSharedPreferences("crypto.KEY_256", Context.MODE_PRIVATE);
                for (Map.Entry<String, ?> entry : sharedPreferences.getAll().entrySet()) {
                    jsonObject.put(entry.getKey(), encodePrefValue(entry.getValue()));
                }
                if (!FileUtils.writeSimple(jsonObject.toString().getBytes("UTF-8"), new File(backup, "hawk"))) {
                    backup.delete();
                    GlassTip.show(getContext(), "备份Hawk失败!");
                } else {
                    GlassTip.show(getContext(), "备份成功!");
                }
            } else {
                GlassTip.show(getContext(), "DB文件不存在!");
                backup.delete();
            }
        } catch (Throwable e) {
            e.printStackTrace();
            GlassTip.show(getContext(), "备份失败!");
        }
    }

    /**
     * 类型标签前缀：\u0001 + 类型字符 + ':'。
     * Hawk 的存储值全是加密后的 Base64 字符串（不含 \u0001），
     * 因此以它为前缀可安全区分类型化值，同时保持与旧备份的向后兼容。
     */
    private static final String TYPE_TAG = "\u0001";

    /** 把 SharedPreferences 里的值编码为 JSON 可承载的字符串：String 原样，其余加类型标签 */
    private static String encodePrefValue(Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Set) {
            JSONArray arr = new JSONArray();
            for (Object o : (Set<?>) value) {
                arr.put(o == null ? "" : String.valueOf(o));
            }
            return TYPE_TAG + "S:" + arr.toString();
        }
        if (value instanceof Boolean) {
            return TYPE_TAG + "b:" + value;
        }
        if (value instanceof Integer) {
            return TYPE_TAG + "i:" + value;
        }
        if (value instanceof Long) {
            return TYPE_TAG + "l:" + value;
        }
        if (value instanceof Float) {
            return TYPE_TAG + "f:" + value;
        }
        return String.valueOf(value);
    }

    void delete(String dir) {
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File backup = new File(root + "/tvbox_backup/" + dir);
            FileUtils.recursiveDelete(backup);
            GlassTip.show(getContext(), "删除成功!");
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}