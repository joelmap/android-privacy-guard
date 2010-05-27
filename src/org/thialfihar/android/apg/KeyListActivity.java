/*
 * Copyright (C) 2010 Thialfihar <thi@thialfihar.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.thialfihar.android.apg;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Vector;

import org.bouncycastle2.openpgp.PGPException;
import org.bouncycastle2.openpgp.PGPPublicKeyRing;
import org.bouncycastle2.openpgp.PGPSecretKeyRing;
import org.thialfihar.android.apg.provider.KeyRings;
import org.thialfihar.android.apg.provider.Keys;
import org.thialfihar.android.apg.provider.UserIds;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ExpandableListView.ExpandableListContextMenuInfo;

public class KeyListActivity extends BaseActivity {
    protected ExpandableListView mList;
    protected KeyListAdapter mListAdapter;

    protected int mSelectedItem = -1;
    protected int mTask = 0;

    protected String mImportFilename = Constants.path.app_dir + "/";
    protected String mExportFilename = Constants.path.app_dir + "/";

    protected int mKeyType = Id.type.public_key;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.key_list);

        mList = (ExpandableListView) findViewById(R.id.list);
        mListAdapter = new KeyListAdapter(this);
        mList.setAdapter(mListAdapter);
        registerForContextMenu(mList);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case Id.menu.option.import_keys: {
                showDialog(Id.dialog.import_keys);
                return true;
            }

            case Id.menu.option.export_keys: {
                showDialog(Id.dialog.export_keys);
                return true;
            }

            default: {
                return super.onOptionsItemSelected(item);
            }
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem menuItem) {
        ExpandableListContextMenuInfo info = (ExpandableListContextMenuInfo) menuItem.getMenuInfo();
        int type = ExpandableListView.getPackedPositionType(info.packedPosition);
        int groupPosition = ExpandableListView.getPackedPositionGroup(info.packedPosition);

        if (type != ExpandableListView.PACKED_POSITION_TYPE_GROUP) {
            return super.onContextItemSelected(menuItem);
        }

        switch (menuItem.getItemId()) {
            case Id.menu.export: {
                mSelectedItem = groupPosition;
                showDialog(Id.dialog.export_key);
                return true;
            }

            case Id.menu.delete: {
                mSelectedItem = groupPosition;
                showDialog(Id.dialog.delete_key);
                return true;
            }

            default: {
                return super.onContextItemSelected(menuItem);
            }
        }
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        boolean singleKeyExport = false;

        switch (id) {
            case Id.dialog.delete_key: {
                final int keyRingId = mListAdapter.getKeyRingId(mSelectedItem);
                mSelectedItem = -1;
                // TODO: better way to do this?
                String userId = "<unknown>";
                Object keyRing = Apg.getKeyRing(keyRingId);
                if (keyRing != null) {
                    if (keyRing instanceof PGPPublicKeyRing) {
                        userId = Apg.getMainUserIdSafe(this, Apg.getMasterKey((PGPPublicKeyRing) keyRing));
                    } else {
                        userId = Apg.getMainUserIdSafe(this, Apg.getMasterKey((PGPSecretKeyRing) keyRing));
                    }
                }

                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(R.string.warning);
                builder.setMessage(getString(mKeyType == Id.type.public_key ?
                                                 R.string.keyDeletionConfirmation :
                                                 R.string.secretKeyDeletionConfirmation, userId));
                builder.setIcon(android.R.drawable.ic_dialog_alert);
                builder.setPositiveButton(R.string.btn_delete,
                                          new DialogInterface.OnClickListener() {
                                              public void onClick(DialogInterface dialog, int id) {
                                                  deleteKey(keyRingId);
                                                  removeDialog(Id.dialog.delete_key);
                                              }
                                          });
                builder.setNegativeButton(android.R.string.cancel,
                                          new DialogInterface.OnClickListener() {
                                              public void onClick(DialogInterface dialog, int id) {
                                                  removeDialog(Id.dialog.delete_key);
                                              }
                                          });
                return builder.create();
            }

            case Id.dialog.import_keys: {
                return FileDialog.build(this, getString(R.string.title_importKeys),
                                        getString(R.string.specifyFileToImportFrom),
                                        mImportFilename,
                                        new FileDialog.OnClickListener() {

                                            @Override
                                            public void onOkClick(String filename) {
                                                removeDialog(Id.dialog.import_keys);
                                                mImportFilename = filename;
                                                importKeys();
                                            }

                                            @Override
                                            public void onCancelClick() {
                                                removeDialog(Id.dialog.import_keys);
                                            }
                                        },
                                        getString(R.string.filemanager_titleOpen),
                                        getString(R.string.filemanager_btnOpen),
                                        Id.request.filename);
            }

            case Id.dialog.export_key: {
                singleKeyExport = true;
                // break intentionally omitted, to use the Id.dialog.export_keys dialog
            }

            case Id.dialog.export_keys: {
                String title = (singleKeyExport ?
                                    getString(R.string.title_exportKey) :
                                    getString(R.string.title_exportKeys));

                final int thisDialogId = (singleKeyExport ? Id.dialog.export_key : Id.dialog.export_keys);

                return FileDialog.build(this, title,
                                        getString(mKeyType == Id.type.public_key ?
                                                      R.string.specifyFileToExportTo :
                                                      R.string.specifyFileToExportSecretKeysTo),
                                        mExportFilename,
                                        new FileDialog.OnClickListener() {
                                            @Override
                                            public void onOkClick(String filename) {
                                                removeDialog(thisDialogId);
                                                mExportFilename = filename;
                                                exportKeys();
                                            }

                                            @Override
                                            public void onCancelClick() {
                                                removeDialog(thisDialogId);
                                            }
                                        },
                                        getString(R.string.filemanager_titleSave),
                                        getString(R.string.filemanager_btnSave),
                                        Id.request.filename);
            }

            default: {
                return super.onCreateDialog(id);
            }
        }
    }

    public void importKeys() {
        showDialog(Id.dialog.importing);
        mTask = Id.task.import_keys;
        startThread();
    }

    public void exportKeys() {
        showDialog(Id.dialog.exporting);
        mTask = Id.task.export_keys;
        startThread();
    }

    @Override
    public void run() {
        String error = null;
        Bundle data = new Bundle();
        Message msg = new Message();

        String filename = null;
        if (mTask == Id.task.import_keys) {
            filename = mImportFilename;
        } else {
            filename = mExportFilename;
        }

        try {
            if (mTask == Id.task.import_keys) {
                data = Apg.importKeyRings(this, mKeyType, filename, this);
            } else {
                Vector<Integer> keyRingIds = new Vector<Integer>();
                if (mSelectedItem == -1) {
                    keyRingIds = Apg.getKeyRingIds(mKeyType == Id.type.public_key ?
                                                       Id.database.type_public :
                                                       Id.database.type_secret);
                } else {
                    int keyRingId = mListAdapter.getKeyRingId(mSelectedItem);
                    keyRingIds.add(keyRingId);
                    mSelectedItem = -1;
                }
                data = Apg.exportKeyRings(this, keyRingIds, filename, this);
            }
        } catch (FileNotFoundException e) {
            error = getString(R.string.error_fileNotFound);
        } catch (IOException e) {
            error = "" + e;
        } catch (PGPException e) {
            error = "" + e;
        } catch (Apg.GeneralException e) {
            error = "" + e;
        }

        if (mTask == Id.task.import_keys) {
            data.putInt("type", Id.message.import_done);
        } else {
            data.putInt("type", Id.message.export_done);
        }

        if (error != null) {
            data.putString("error", error);
        }

        msg.setData(data);
        sendMessage(msg);
    }

    protected void deleteKey(int keyRingId) {
        Apg.deleteKey(keyRingId);
        refreshList();
    }

    protected void refreshList() {
        mListAdapter.rebuild(true);
        mListAdapter.notifyDataSetChanged();
    }

    @Override
    public void doneCallback(Message msg) {
        super.doneCallback(msg);

        Bundle data = msg.getData();
        if (data != null) {
            int type = data.getInt("type");
            switch (type) {
                case Id.message.import_done: {
                    removeDialog(Id.dialog.importing);

                    String error = data.getString("error");
                    if (error != null) {
                        Toast.makeText(KeyListActivity.this,
                                       getString(R.string.errorMessage, data.getString("error")),
                                       Toast.LENGTH_SHORT).show();
                    } else {
                        int added = data.getInt("added");
                        int updated = data.getInt("updated");
                        String message;
                        if (added > 0 && updated > 0) {
                            message = getString(R.string.keysAddedAndUpdated, added, updated);
                        } else if (added > 0) {
                            message = getString(R.string.keysAdded, added);
                        } else if (updated > 0) {
                            message = getString(R.string.keysUpdated, updated);
                        } else {
                            message = getString(R.string.noKeysAddedOrUpdated);
                        }
                        Toast.makeText(KeyListActivity.this, message,
                                       Toast.LENGTH_SHORT).show();
                    }
                    refreshList();
                    break;
                }

                case Id.message.export_done: {
                    removeDialog(Id.dialog.exporting);

                    String error = data.getString("error");
                    if (error != null) {
                        Toast.makeText(KeyListActivity.this,
                                       getString(R.string.errorMessage, data.getString("error")),
                                       Toast.LENGTH_SHORT).show();
                    } else {
                        int exported = data.getInt("exported");
                        String message;
                        if (exported == 1) {
                            message = getString(R.string.keyExported);
                        } else if (exported > 0) {
                            message = getString(R.string.keysExported, exported);
                        } else{
                            message = getString(R.string.noKeysExported);
                        }
                        Toast.makeText(KeyListActivity.this, message,
                                       Toast.LENGTH_SHORT).show();
                    }
                    break;
                }

                default: {
                    break;
                }
            }
        }
    }

    protected class KeyListAdapter extends BaseExpandableListAdapter {
        private LayoutInflater mInflater;
        private Vector<Vector<KeyChild>> mChildren;
        private SQLiteDatabase mDatabase;
        private Cursor mCursor;

        private class KeyChild {
            public static final int KEY = 0;
            public static final int USER_ID = 1;

            public int type;
            public String userId;
            public long keyId;
            public boolean isMasterKey;
            public int algorithm;
            public int keySize;
            public boolean canSign;
            public boolean canEncrypt;

            public KeyChild(long keyId, boolean isMasterKey, int algorithm, int keySize,
                            boolean canSign, boolean canEncrypt) {
                this.keyId = keyId;
                this.isMasterKey = isMasterKey;
                this.algorithm = algorithm;
                this.keySize = keySize;
                this.canSign = canSign;
                this.canEncrypt = canEncrypt;
            }

            public KeyChild(String userId) {
                type = USER_ID;
                this.userId = userId;
            }
        }

        public KeyListAdapter(Context context) {
            mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mDatabase = Apg.getDatabase().db();
            mCursor = mDatabase.query(
                    KeyRings.TABLE_NAME + " INNER JOIN " + Keys.TABLE_NAME + " ON " +
                                          "(" + KeyRings.TABLE_NAME + "." + KeyRings._ID + " = " +
                                          Keys.TABLE_NAME + "." + Keys.KEY_RING_ID + " AND " +
                                          Keys.TABLE_NAME + "." + Keys.IS_MASTER_KEY + " = '1'" +
                                          ") " +
                                          " INNER JOIN " + UserIds.TABLE_NAME + " ON " +
                                          "(" + Keys.TABLE_NAME + "." + Keys._ID + " = " +
                                          UserIds.TABLE_NAME + "." + UserIds.KEY_ID + " AND " +
                                          UserIds.TABLE_NAME + "." + UserIds.RANK + " = '0') ",
                    new String[] {
                        KeyRings.TABLE_NAME + "." + KeyRings._ID,           // 0
                        KeyRings.TABLE_NAME + "." + KeyRings.MASTER_KEY_ID, // 1
                        UserIds.TABLE_NAME + "." + UserIds.USER_ID,         // 2
                    },
                    KeyRings.TABLE_NAME + "." + KeyRings.TYPE + " = ?",
                    new String[] { "" + (mKeyType == Id.type.public_key ?
                                             Id.database.type_public : Id.database.type_secret) },
                    null, null, UserIds.TABLE_NAME + "." + UserIds.USER_ID + " ASC");

            rebuild(false);
        }

        @Override
        protected void finalize() throws Throwable {
            mCursor.deactivate();
            super.finalize();
        }

        public void rebuild(boolean requery) {
            if (requery) {
                mCursor.requery();
            }
            mChildren = new Vector<Vector<KeyChild>>();
            for (int i = 0; i < mCursor.getCount(); ++i) {
                mChildren.add(null);
            }
        }

        protected Vector<KeyChild> getChildrenOfGroup(int groupPosition) {
            Vector<KeyChild> children = mChildren.get(groupPosition);
            if (children != null) {
                return children;
            }

            mCursor.moveToPosition(groupPosition);
            children = new Vector<KeyChild>();
            Cursor c = mDatabase.query(Keys.TABLE_NAME,
                    new String[] {
                        Keys._ID,           // 0
                        Keys.KEY_ID,        // 1
                        Keys.IS_MASTER_KEY, // 2
                        Keys.ALGORITHM,     // 3
                        Keys.KEY_SIZE,      // 4
                        Keys.CAN_SIGN,      // 5
                        Keys.CAN_ENCRYPT,   // 6
                    },
                    Keys.KEY_RING_ID + " = ?",
                    new String[] { mCursor.getString(0) },
                    null, null, Keys.RANK + " ASC");

            long masterKeyId = -1;
            for (int i = 0; i < c.getCount(); ++i) {
                c.moveToPosition(i);
                children.add(new KeyChild(c.getLong(1), c.getInt(2) == 1, c.getInt(3), c.getInt(4),
                                          c.getInt(5) == 1, c.getInt(6) == 1));
                if (i == 0) {
                    masterKeyId = c.getInt(0);
                }
            }
            c.close();

            if (masterKeyId != -1) {
                c = mDatabase.query(UserIds.TABLE_NAME,
                         new String[] {
                             UserIds.USER_ID, // 0
                         },
                         UserIds.KEY_ID + " = ? AND " + UserIds.RANK + " > 0",
                         new String[] { "" + masterKeyId },
                         null, null, UserIds.RANK + " ASC");

                 for (int i = 0; i < c.getCount(); ++i) {
                     c.moveToPosition(i);
                     children.add(new KeyChild(c.getString(0)));
                 }
                 c.close();
            }

            mChildren.set(groupPosition, children);
            return children;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public boolean isChildSelectable(int groupPosition, int childPosition) {
            return true;
        }

        public int getGroupCount() {
            return mCursor.getCount();
        }

        public Object getChild(int groupPosition, int childPosition) {
            return null;
        }

        public long getChildId(int groupPosition, int childPosition) {
            return childPosition;
        }

        public int getChildrenCount(int groupPosition) {
            return getChildrenOfGroup(groupPosition).size();
        }

        public Object getGroup(int position) {
            return position;
        }

        public long getGroupId(int position) {
            mCursor.moveToPosition(position);
            return mCursor.getLong(1); // MASTER_KEY_ID
        }

        public int getKeyRingId(int position) {
            mCursor.moveToPosition(position);
            return mCursor.getInt(0); // _ID
        }

        public View getGroupView(int groupPosition, boolean isExpanded, View convertView,
                                 ViewGroup parent) {
            mCursor.moveToPosition(groupPosition);

            View view = mInflater.inflate(R.layout.key_list_group_item, null);
            view.setBackgroundResource(android.R.drawable.list_selector_background);

            TextView mainUserId = (TextView) view.findViewById(R.id.mainUserId);
            mainUserId.setText("");
            TextView mainUserIdRest = (TextView) view.findViewById(R.id.mainUserIdRest);
            mainUserIdRest.setText("");

            String userId = mCursor.getString(2); // USER_ID
            if (userId != null) {
                String chunks[] = userId.split(" <", 2);
                userId = chunks[0];
                if (chunks.length > 1) {
                    mainUserIdRest.setText("<" + chunks[1]);
                }
                mainUserId.setText(userId);
            }

            if (mainUserId.getText().length() == 0) {
                mainUserId.setText(R.string.unknownUserId);
            }

            if (mainUserIdRest.getText().length() == 0) {
                mainUserIdRest.setVisibility(View.GONE);
            }
            return view;
        }

        public View getChildView(int groupPosition, int childPosition,
                                 boolean isLastChild, View convertView,
                                 ViewGroup parent) {
            mCursor.moveToPosition(groupPosition);

            Vector<KeyChild> children = getChildrenOfGroup(groupPosition);

            KeyChild child = children.get(childPosition);
            View view = null;
            switch (child.type) {
                case KeyChild.KEY: {
                    if (child.isMasterKey) {
                        view = mInflater.inflate(R.layout.key_list_child_item_master_key, null);
                    } else {
                        view = mInflater.inflate(R.layout.key_list_child_item_sub_key, null);
                    }

                    TextView keyId = (TextView) view.findViewById(R.id.keyId);
                    String keyIdStr = Long.toHexString(child.keyId & 0xffffffffL);
                    while (keyIdStr.length() < 8) {
                        keyIdStr = "0" + keyIdStr;
                    }
                    keyId.setText(keyIdStr);
                    TextView keyDetails = (TextView) view.findViewById(R.id.keyDetails);
                    String algorithmStr = Apg.getAlgorithmInfo(child.algorithm, child.keySize);
                    keyDetails.setText("(" + algorithmStr + ")");

                    ImageView encryptIcon = (ImageView) view.findViewById(R.id.ic_encryptKey);
                    if (!child.canEncrypt) {
                        encryptIcon.setVisibility(View.GONE);
                    }

                    ImageView signIcon = (ImageView) view.findViewById(R.id.ic_signKey);
                    if (!child.canSign) {
                        signIcon.setVisibility(View.GONE);
                    }
                    break;
                }

                case KeyChild.USER_ID: {
                    view = mInflater.inflate(R.layout.key_list_child_item_user_id, null);
                    TextView userId = (TextView) view.findViewById(R.id.userId);
                    userId.setText(child.userId);
                    break;
                }
            }
            return view;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case Id.request.filename: {
                if (resultCode == RESULT_OK && data != null) {
                    String filename = data.getDataString();
                    if (filename != null) {
                        // Get rid of URI prefix:
                        if (filename.startsWith("file://")) {
                            filename = filename.substring(7);
                        }
                        // replace %20 and so on
                        filename = Uri.decode(filename);

                        FileDialog.setFilename(filename);
                    }
                }
                return;
            }

            default: {
                break;
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}