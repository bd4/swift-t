# Copyright 2013 University of Chicago and Argonne National Laboratory
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License

# Test what happens if we try to get something that does not exist

package require turbine 0.0.1
adlb::init 1 1

if [ adlb::amserver ] {
    adlb::server
} else {
    set z  0
    set d0 [ adlb::unique ]
    set d1 [ adlb::unique ]
    adlb::create $d1 $adlb::INTEGER 0
    set d2 [ adlb::unique ]
    adlb::create $d2 $adlb::INTEGER 0
    # Don't decrement writer
    adlb::store $d2 $adlb::INTEGER 25 0
    set d3 [ adlb::unique ]
    adlb::create $d3 $adlb::INTEGER 0
    adlb::store $d3 $adlb::INTEGER 35
    set L [ list $z $d0 $d1 $d2 $d3 ]
    foreach d $L {
        if { [ adlb::exists $d ] } {
            puts "exists: $d"
        } else {
            puts "nope: $d"
        }
    }
}

adlb::finalize
puts OK

proc exit args {}
