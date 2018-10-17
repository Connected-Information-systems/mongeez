/*
 * Copyright 2011 SecondMarket Labs, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at  http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.mongeez.commands

import org.mongeez.dao.MongeezDao

/**
 * @author oleksii
 * @since 5/3/11
 */
class Script() {
    lateinit var body: String

    constructor(body: String) : this() {
        this.body = body
    }

    fun run(dao: MongeezDao, util: String?) {
        dao.runScript(body, util)
    }
}
