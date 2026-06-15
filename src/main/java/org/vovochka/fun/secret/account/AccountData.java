package org.vovochka.fun.secret.account;

public class AccountData {
    public final String username;
    public final String password;
    public final boolean isBank;

    public AccountData(String username, String password, boolean isBank) {
        this.username = username;
        this.password = password;
        this.isBank = isBank;
    }

    @Override
    public String toString() {
        return "AccountData{username='" + username + "', isBank=" + isBank + "}";
    }
}