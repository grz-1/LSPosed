package org.lsposed.lspd.models;

import android.content.pm.PackageInfo;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseBooleanArray;
import java.util.Objects;

public class ModuleInfo implements Parcelable {
    public static final Parcelable.Creator<ModuleInfo> CREATOR = new h0(18);
    public final String b;
    public final String c;
    public final boolean d;
    public final int e;
    public final SparseBooleanArray f;
    public final PackageInfo g;

    public ModuleInfo(Parcel parcel) {
        String readString = parcel.readString();
        Objects.requireNonNull(readString);
        this.b = readString;
        String readString2 = parcel.readString();
        Objects.requireNonNull(readString2);
        this.c = readString2;
        this.d = parcel.readByte() != 0;
        this.e = parcel.readInt();
        SparseBooleanArray readSparseBooleanArray = parcel.readSparseBooleanArray();
        Objects.requireNonNull(readSparseBooleanArray);
        this.f = readSparseBooleanArray;
        this.g = (PackageInfo) parcel.readParcelable(PackageInfo.class.getClassLoader());
    }

    @Override
    public final int describeContents() {
        return 0;
    }

    @Override
    public final void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(this.b);
        parcel.writeString(this.c);
        parcel.writeByte(this.d ? (byte) 1 : (byte) 0);
        parcel.writeInt(this.e);
        parcel.writeSparseBooleanArray(this.f);
        parcel.writeParcelable(this.g, i);
    }
}
