/*
 * Copyright (c) 2012 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

package com.haulmont.cuba.desktop.exception;

import com.haulmont.cuba.core.global.FileStorageException;
import com.haulmont.cuba.core.global.MessageProvider;
import com.haulmont.cuba.desktop.App;
import com.haulmont.cuba.gui.components.IFrame;

import javax.annotation.Nullable;

/**
 * @author krivopustov
 * @version $Id$
 */
public class FileStorageExceptionHandler extends AbstractExceptionHandler {

    public FileStorageExceptionHandler() {
        super(FileStorageException.class.getName());
    }

    @Override
    protected void doHandle(Thread thread, String className, String message, @Nullable Throwable throwable) {
        String msg = null;
        if (throwable != null) {
            FileStorageException storageException = (FileStorageException) throwable;
            String fileName = storageException.getFileName();
            if (storageException.getType().equals(FileStorageException.Type.FILE_NOT_FOUND))
                msg = MessageProvider.formatMessage(getClass(), "fileNotFound.message", fileName);
            else if (storageException.getType().equals(FileStorageException.Type.STORAGE_INACCESSIBLE))
                msg = MessageProvider.getMessage(getClass(), "fileStorageInaccessible.message");
        }
        if (msg == null) {
            msg = MessageProvider.getMessage(getClass(), "fileStorageException.message");
        }
        App.getInstance().getMainFrame().showNotification(msg, IFrame.NotificationType.ERROR);
    }
}
