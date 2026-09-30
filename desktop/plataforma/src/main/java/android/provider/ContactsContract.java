package android.provider;

import android.net.Uri;

/** Sustituto: las constantes que arma la pantalla de cliente nuevo; en escritorio la agenda nunca contesta. */
public final class ContactsContract {
    private ContactsContract() {}

    public static final class Contacts {
        private Contacts() {}
        public static final Uri CONTENT_URI = Uri.parse("content://com.android.contacts/contacts");
        public static final String _ID = "_id";
        public static final String DISPLAY_NAME = "display_name";
    }

    public static final class CommonDataKinds {
        private CommonDataKinds() {}

        public static final class Phone {
            private Phone() {}
            public static final Uri CONTENT_URI = Uri.parse("content://com.android.contacts/data/phones");
            public static final String CONTACT_ID = "contact_id";
            public static final String NUMBER = "data1";
        }

        public static final class Email {
            private Email() {}
            public static final Uri CONTENT_URI = Uri.parse("content://com.android.contacts/data/emails");
            public static final String CONTACT_ID = "contact_id";
            public static final String ADDRESS = "data1";
        }
    }
}
