/*
 * Copyright (c) 2008 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.

 * Author: Konstantin Krivopustov
 * Created: 26.12.2008 10:17:29
 *
 * $Id$
 */
package com.haulmont.cuba.core.global;

import java.util.Set;

public interface QueryTransformer
{
    /** Adds 'where' clause replacing entity alias */
    void addWhere(String where);

    /** Adds 'join' and 'where' clauses. Does not replace entity aliases */
    void addJoinAndWhere(String join, String where);

    /** Adds 'where' clause from the query provided. Replaces entity alias */
    void mergeWhere(String query);

    /** Replaces <code>select e.f1, e.f2, ...</code> clause with <code>select count(e) ...</code> */
    void replaceWithCount();

    /** Adds or replaces 'order by' clause */
    void replaceOrderBy(String property, boolean asc);

    /** Reset internal buffer */
    void reset();

    /** Get buffer */
    String getResult();

    /** Get parameter names found during transformation */
    Set<String> getAddedParams();
}
