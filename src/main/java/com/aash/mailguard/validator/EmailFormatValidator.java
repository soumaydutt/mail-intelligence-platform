package com.aash.mailguard.validator;

import org.springframework.stereotype.Component;

@Component
public class EmailFormatValidator {
    public boolean isFormatValid(String email) {

        if (email == null || email.isBlank()) {
            return false;
        }
        for (int i = 0; i < email.length(); i++) {
            if (Character.isISOControl(email.charAt(i))) {
                return false;
            }
        }


        int atIndex = email.indexOf("@");

        if (atIndex <= 0) {
            return false;
        }


        if (atIndex != email.lastIndexOf("@")) {
            return false;
        }


        if (atIndex == email.length() - 1) {
            return false;
        }


        int dotIndex = email.indexOf(".", atIndex + 1);

        if (dotIndex == -1) {
            return false;
        }

        if (dotIndex==atIndex + 1){
            return false;
        }


        if (dotIndex == email.length() - 1) {
            return false;
        }

        return true;
    }
}
