/*
 * Copyright (c) 2014, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 3 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 3 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.r.runtime.env.frame;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.r.runtime.RError;
import com.oracle.truffle.r.runtime.RError.Message;
import com.oracle.truffle.r.runtime.RLocale;
import com.oracle.truffle.r.runtime.RRuntime;
import com.oracle.truffle.r.runtime.context.RContext;
import com.oracle.truffle.r.runtime.data.RDataFactory;
import com.oracle.truffle.r.runtime.data.RMissing;
import com.oracle.truffle.r.runtime.data.RStringVector;
import com.oracle.truffle.r.runtime.env.REnvironment;
import com.oracle.truffle.r.runtime.env.REnvironment.PutException;

/**
 * Variant of {@link REnvFrameAccess} that provides access to an actual Truffle execution frame.
 */
public final class REnvTruffleFrameAccess extends REnvFrameAccess {

    private final MaterializedFrame frame;
    /**
     * Records which bindings are locked. In normal use we don't expect any bindings to be locked so
     * this set is allocated lazily.
     */
    private Set<String> lockedBindings;

    public REnvTruffleFrameAccess(MaterializedFrame frame) {
        this.frame = frame;
    }

    @Override
    public MaterializedFrame getFrame() {
        return frame;
    }

    @Override
    public Object get(String key) {
        CompilerAsserts.neverPartOfCompilation();
        FrameDescriptor fd = frame.getFrameDescriptor();
        FrameSlot slot = fd.findFrameSlot(key);
        if (slot == null) {
            return null;
        } else {
            Object value = FrameSlotChangeMonitor.getValue(slot, frame);
            // special treatment for active binding: call bound function
            if (ActiveBinding.isActiveBinding(value)) {
                Object readValue = ((ActiveBinding) value).readValue();
                // special case: if the active binding returns RMissing, then this should behave
                // like the variable does not exist.
                return readValue != RMissing.instance ? readValue : null;
            }
            return value;
        }
    }

    @Override
    public void put(String key, Object value) throws PutException {
        CompilerAsserts.neverPartOfCompilation();
        assert key != null;
        assert value != null;
        if (lockedBindings != null && lockedBindings.contains(key)) {
            throw new PutException(RError.Message.ENV_CHANGE_BINDING, key);
        }
        FrameSlotKind valueSlotKind = RRuntime.getSlotKind(value);
        FrameDescriptor fd = frame.getFrameDescriptor();
        FrameSlot slot = FrameSlotChangeMonitor.findOrAddFrameSlot(fd, key, valueSlotKind);

        if (valueSlotKind != slot.getKind()) {
            // we must not toggle between slot kinds, so go to Object
            valueSlotKind = FrameSlotKind.Object;
            slot.setKind(valueSlotKind);
        }

        switch (valueSlotKind) {
            case Byte:
                FrameSlotChangeMonitor.setByteAndInvalidate(frame, slot, (byte) value, false, null);
                break;
            case Int:
                FrameSlotChangeMonitor.setIntAndInvalidate(frame, slot, (int) value, false, null);
                break;
            case Double:
                FrameSlotChangeMonitor.setDoubleAndInvalidate(frame, slot, (double) value, false, null);
                break;
            case Object:
                Object object;
                try {
                    object = frame.getObject(slot);
                } catch (FrameSlotTypeException e) {
                    object = null;
                }

                if (object != null && ActiveBinding.isActiveBinding(object)) {
                    ((ActiveBinding) object).writeValue(value);
                } else {
                    FrameSlotChangeMonitor.setObjectAndInvalidate(frame, slot, value, false, null);
                }
                break;
            case Illegal:
                break;
            default:
                throw new PutException(Message.GENERIC, "frame slot exception");
        }
    }

    @Override
    public void rm(String key) throws PutException {
        CompilerAsserts.neverPartOfCompilation();
        assert key != null;
        if (lockedBindings != null) {
            lockedBindings.remove(key);
        }
        FrameDescriptor fd = frame.getFrameDescriptor();
        FrameSlot slot = fd.findFrameSlot(key);

        // Handle all other values
        if (slot == null) {
            // TODO: also throw this error when slot contains "null" value
            throw new PutException(RError.Message.UNKNOWN_OBJECT, key);
        } else {
            if (slot.getKind() != FrameSlotKind.Object) {
                slot.setKind(FrameSlotKind.Object);
            }

            Assumption containsNoActiveBindingAssumption = FrameSlotChangeMonitor.getContainsNoActiveBindingAssumption(fd);
            Object result = null;
            // special treatment for active binding: call bound function
            try {
                if (!containsNoActiveBindingAssumption.isValid() && ActiveBinding.isActiveBinding(result = FrameSlotChangeMonitor.getObject(slot, frame))) {
                    ActiveBinding binding = (ActiveBinding) result;
                    if (binding.isHidden()) {
                        binding.setInitialized(false);
                        return;
                    }
                }
            } catch (FrameSlotTypeException e) {
                // ignore
            }

            FrameSlotChangeMonitor.setObjectAndInvalidate(frame, slot, null, false, null);
        }
    }

    @Override
    @TruffleBoundary
    public RStringVector ls(boolean allNames, Pattern pattern, boolean sorted) {
        FrameDescriptor fd = frame.getFrameDescriptor();
        String[] names = getStringIdentifiers(fd);
        ArrayList<String> matchedNamesList = new ArrayList<>(names.length);
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            FrameSlot frameSlot = fd.findFrameSlot(name);
            Object value = FrameSlotChangeMonitor.getValue(frameSlot, frame);
            if (value == null || !ActiveBinding.isListed(value)) {
                continue;
            }
            if (REnvironment.includeName(name, allNames, pattern)) {
                matchedNamesList.add(name);
            }
        }
        String[] data = new String[matchedNamesList.size()];
        matchedNamesList.toArray(data);
        if (sorted) {
            Locale locale = RContext.getInstance().stateRLocale.getLocale(RLocale.COLLATE);
            Collator collator = locale == Locale.ROOT || locale == null ? null : RLocale.getOrderCollator(locale);
            Arrays.sort(data, new Comparator<String>() {
                @Override
                public int compare(String o1, String o2) {
                    return RLocale.compare(collator, o1, o2);
                }
            });
        }
        return RDataFactory.createStringVector(data, RDataFactory.COMPLETE_VECTOR);
    }

    @Override
    @TruffleBoundary
    public boolean bindingIsLocked(String key) {
        return lockedBindings != null && lockedBindings.contains(key);
    }

    @Override
    @TruffleBoundary
    public void lockBindings() {
        for (Object binding : frame.getFrameDescriptor().getIdentifiers()) {
            if (binding instanceof String) {
                lockBinding((String) binding);
            }
        }
    }

    @Override
    @TruffleBoundary
    public void lockBinding(String key) {
        if (lockedBindings == null) {
            lockedBindings = new HashSet<>();
        }
        lockedBindings.add(key);
    }

    @Override
    @TruffleBoundary
    public void unlockBinding(String key) {
        if (lockedBindings != null) {
            lockedBindings.remove(key);
        }
    }

    public static String[] getStringIdentifiers(FrameDescriptor fd) {
        return fd.getIdentifiers().stream().filter(e -> (e instanceof String)).toArray(String[]::new);
    }
}
