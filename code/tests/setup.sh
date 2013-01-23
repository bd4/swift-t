
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
# Re-usable test setup lines
# Helps automate selection of process mode (engine, server, worker)
# Prints the "SETUP:" header in the *.out file

export ADLB_EXHAUST_TIME=1

if [[ ${TURBINE_ENGINES} == "" ]]
then
  export TURBINE_ENGINES=1
fi

if [[ ${TURBINE_WORKERS} == "" ]]
then
  export TURBINE_WORKERS=1
fi

if [[ ${ADLB_SERVERS} == "" ]]
then
  export ADLB_SERVERS=1
fi

PROCS=$(( ${TURBINE_ENGINES} + ${TURBINE_WORKERS} + ${ADLB_SERVERS} ))

display()
{
  T=$1
  I=$2
  J=$3
  V=$( eval echo \$${T} )
  printf "%-16s %3i RANKS: %3i - %3i\n" ${T}: ${V} ${I} ${J}
}

TURBINE_RANKS=$(( ${TURBINE_ENGINES} + ${TURBINE_WORKERS} ))

echo SETUP:
date "+%m/%d/%Y %I:%M%p"
display TURBINE_ENGINES 0 $(( TURBINE_ENGINES-1 ))
display TURBINE_WORKERS ${TURBINE_ENGINES} $(( TURBINE_RANKS-1 ))
display ADLB_SERVERS ${TURBINE_RANKS} $(( PROCS-1 ))
echo PROCS: ${PROCS}
echo
