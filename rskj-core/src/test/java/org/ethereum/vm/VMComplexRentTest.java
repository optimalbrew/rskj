/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.ethereum.vm;

import co.rsk.config.TestSystemProperties;
import co.rsk.config.VmConfig;
import co.rsk.core.Coin;
import co.rsk.core.RskAddress;
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.config.blockchain.upgrades.ActivationConfig;
import org.ethereum.core.AccountState;
import org.ethereum.core.BlockFactory;
import org.ethereum.core.Repository;
import org.ethereum.crypto.HashUtil;
import org.ethereum.vm.program.Program;
import org.ethereum.vm.program.ProgramResult;
import org.ethereum.vm.program.invoke.ProgramInvoke;
import org.ethereum.vm.program.invoke.ProgramInvokeMockImpl;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.Set;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * @author Roman Mandeleil
 * @since 16.06.2014
 * duplicated and modified by s mishra for storage rent.. December 2020

 * #mish notes
 * - Derived from VMComplexTest. In master branch, these tests are ignored. Couple were failing (gas passed 1K < 20K needed for sstore)
 * - Gas used assertions will fail (set too low)
 * - Added repository.setupContract(); for all contracts.. without these, isContract() returns false.. needed for rent tracker  
 * More recent versions: Differences betwheen this class and the ones developed by Seba + Juli for Create2, ExtCodeHash.
    - this one uses null for transaction when instantiating a new program = in getProgram()
* - https://ethervm.io/decompile was useful in helping fix.. and also just printing out the gasused for each VM step 
    - in Program spendGas to isolate (should have guessed SSTORE was triggering the errors.. oh well)
* These tests alow us to save code directly.. and run within programs. But we cannot choose which parts of the program to run
.. there are no function selectors here.. we run the vm directly
* actual contract interactions would only run relevant parts of the bytecode.. marked by function signature hashes    
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VMComplexRentTest {

    private static Logger logger = LoggerFactory.getLogger("TCK-Test");
    private final TestSystemProperties config = new TestSystemProperties();
    private final BlockFactory blockFactory = new BlockFactory(config.getActivationConfig());
    private final VmConfig vmConfig = config.getVmConfig();
    private final PrecompiledContracts precompiledContracts = new PrecompiledContracts(config, null);


    //#mish .. the CALL is NOT recursive as in increasing call depth.
    // rather the program simply resends  
    //@Ignore //TODO #POC9 
    @Test // contract call recursive
    public void test1() {

        /**
         *       #The code will run
         *       ------------------

                 a = contract.storage[999]
                 if a > 0:
                     contract.storage[999] = a - 1

                     # call to contract: 77045e71a7a2c50903d88e564cd72fab11e82051
                     send((tx.gas / 10 * 8), 0x77045e71a7a2c50903d88e564cd72fab11e82051, 0)
                 else:
                     stop
         */

        int expectedGas = 436; //#mish way too low. This is an old example

        DataWord key1 = DataWord.valueOf(999);
        DataWord value1 = DataWord.valueOf(3); // orig is 3. works up to 17.. then OOGs 

        DataWord key2 = DataWord.valueOf(998);
        DataWord value2 = DataWord.valueOf(1);

        // Set contract into Database
        String callerAddr = "cd2a3d9f938e13cd947ec05abc7fe734df8dd826";
        String contractAddr = "77045e71a7a2c50903d88e564cd72fab11e82051";
        String code = //"6103e75460005260006000511115630000004c576001600051036103e755600060006000600060007377045e71a7a2c50903d88e564cd72fab11e820516008600a5a0402f1630000004c00565b00";
                "61" + "03e7" +  //push1 999 (storage key)
                "5460005260006000511115630000004c57600160005103" + "61"+"03e7"+"55" + 
                "6000600060006000" + //push1 s 0 bytes? .. for func signature?
                "6000" + //push1 0 .. msg.value
                "73"+ // push20 for the address
                "77045e71a7a2c50903d88e564cd72fab11e82051" + // contractB address
                "60"+"08" +"60" +"0a" + //push1 8 and then push1 10
                "5a" + // GAS
                "04" + "02" + //divide and multiply (i.e. gas/10 * 8/) // pass 80% avail gas?
                "f1" + // CALL 
                "63" + "0000004c" + //push4 .. this is a jump dest
                "00565b00";
        // Order: CALL : gas->addr->value->argsOffset->argsLength>retOffset->retLengt

        /**
         * contract Contract {
                function main() {
                memory[0x00:0x20] = storage[0x03e7]; //this assignment is MSTORE from SLOAD
            
                if (memory[0x00:0x20] <= 0x00) { stop(); } //MLOAD and GT comp
                    
                storage[0x03e7] = memory[0x00:0x20] - 0x01; //RESET_SSTORE from MLOAD
                var temp0; //unitialized, will store return from call
                // MSTORE from CALL (gas, Calldata)
                temp0, memory[0x00:0x00] = address(0x77045e71a7a2c50903d88e564cd72fab11e82051).call.gas(msg.gas / 0x0a * 0x08)(memory[0x00:0x00]);
                var var1 = 0x0000004c;
                stop();
            }
          }
         */

        RskAddress contractAddrB = new RskAddress(contractAddr);
        RskAddress callerAddrB = new RskAddress(callerAddr);
        byte[] codeB = Hex.decode(code);

        byte[] codeKey = HashUtil.keccak256(codeB);
        AccountState accountState = new AccountState();
        //accountState.setCodeHash(codeKey);

        ProgramInvokeMockImpl pi = new ProgramInvokeMockImpl();
        pi.setOwnerAddress(contractAddrB);
        Repository repository = pi.getRepository();

        repository.createAccount(callerAddrB);
        final BigInteger value = new BigInteger("100000000000000000000");
        repository.addBalance(callerAddrB, new Coin(value));

        repository.createAccount(contractAddrB);
        repository.setupContract(contractAddrB); //#mish: add this.. else isContract() returns false!!
        repository.saveCode(contractAddrB, codeB);
        repository.addStorageRow(contractAddrB, key1, value1);
        repository.addStorageRow(contractAddrB, key2, value2);

        // Play the program
        VM vm = getSubject();
        Program program = getProgram(codeB, pi);

        try {
            while (!program.isStopped())
                vm.step(program);
        } catch (RuntimeException e) {
            program.setRuntimeFailure(e);
        }

        //System.out.println();
        //System.out.println("============ Results ============");

        Coin balance = repository.getBalance(callerAddrB);

        //System.out.println("*** Used gas: " + program.getResult().getGasUsed());
        //System.out.println("*** Contract Balance: " + balance);

        // todo: assert caller balance after contract exec

        ProgramResult result = program.getResult();
                
        String logString = "\n============ Results ============" +"\n" +
                    "Gas used: " + result.getGasUsed() + "\n" +
                    "Rent gas used: " + result.getRentGasUsed() + "\n" +
                    "No. trie nodes with `updated` rent timestamp: " +  result.getAccessedNodes().size() +
                    "\nNew trie nodes created (6 months rent): " +  result.getCreatedNodes().size() + 
                    "\nTotal trie nodes touched by tx: " + result.getKeysSeenBefore().size() + "\n";

        logger.info(logString);
        // this original gas assertion is wrong.. way too low
        //assertEquals(expectedGas, program.getResult().getGasUsed());
    }
    
    
    
    /** Program will play contractB calling contractA with data. 
     * Sends 3 values (11,22,33). But contract A will only store two: msg.data[0] and msg.data[1]*/ 
    //@Ignore //TODO #POC9 #mish: Ignore is from before. Documentation was unclear.. 
    @Test 
    public void test2() {

        /**
         *       #The code will run
         *       ------------------

                 contract A: 77045e71a7a2c50903d88e564cd72fab11e82051
                 ---------------
                     a = msg.data[0]
                     b = msg.data[1]

                     contract.storage[a]
                     contract.storage[b]


                 contract B: 83c5541a6c8d2dbad642f385d8d06ca9b6c731ee
                 -----------
                     a = msg((tx.gas / 10 * 8), 0x77045e71a7a2c50903d88e564cd72fab11e82051, 0, [11, 22, 33], 3, 6)
                     //#mish: this is 10/8 stuff WRONG.. the code actually passes specific gas to CALl.. was 1000
                     // and test was failing.. need to increase > 20000 for SSTORE

         */

        // Set contract into Database
        //String caller_addr_str = "cd2a3d9f938e13cd947ec05abc7fe734df8dd826";

        String contractA_addr_str = "77045e71a7a2c50903d88e564cd72fab11e82051";
        String contractB_addr_str = "83c5541a6c8d2dbad642f385d8d06ca9b6c731ee";

        // original
        //String code_a = "60006020023560005260016020023560205260005160005560205160015500";
        
        String code_a = "6000" + "6020"+ //push1 0, push1 20
                            "02"+ // MUL (multiply 0 with 32) .. could hv skipped 6020 02, MUL used for composition
                        "35" + //CALLDATALOAD
                        "6000" + "52" +// push1 0, MSTORE
                        "6001" + "6020"+ //push1 1, push1 20 .. could have been 4*8 i.e. 6004 6008 02, 
                            "02" + // MUL (multiply 1 with 32) .. or just 6020 instead of without 6001 6020 02
                        "35" +  //CALLDATALOAD
                        "6020" + "52" + //push1 20, another MSTORE
                            "6000" + "51" + //push1 0, MLOAD (32 bytes) starting pos 0, 
                        "6000" + "55" + //push1 0 (KEY), SSTORE 
                            "6020" + "51" + //push1 20, MLOAD (32 bytes)starting pos 0x20 (i.e 32)
                        "6001" + "55" + // push1 1 (KEY), SSTORE
                            "00"; //stop
        
        /** #mish decompiled
         * push1 0 push1 20 
         * contract Contract {
            function main() {
                memory[0x00:0x20] = msg.data[0x00:0x20]; //32 bytes data
                memory[0x20:0x40] = msg.data[0x20:0x40]; // -do- 
                storage[0x00] = memory[0x00:0x20]; //store in cell 1
                storage[0x01] = memory[0x20:0x40]; //store in cell 2
                stop();
                }
            }
         */
        
         //orig
        //String code_b =  "6000601f5360e05960e05952600060c05901536060596020015980602001600b9052806040016016905280606001602190526080905260007377045e71a7a2c50903d88e564cd72fab11e820516203e800f1602060000260a0016020015160005200";
         
        String code_b = "6000" +"601f" + "53" + //push1 0 (value), push1 31 (offset) to MSTORE8 
        "60e0"+ "59" + //push1 224 (value)  MSIZE (offset)
        "60e0" +"59" + //and again
        "52" + //MSTORE
        "6000" + "60c0" + "59" + "01" +"53" + //MSTORE8 (offset: MSIZE + 0xc0, value:00 ) //
        "6060" + "59" + "6020" + "01" + //
        "59" + //MSIZE current memory size, this will be reference for setting offsets
        "80" + //duplicate it, so it is available, after each use of it as a reference
            "6020" + "01" + "600b" +//add 20 to duplicated MSIZE, then push1 0x0b (11) to stack
                "90" + "52" + // then 90: SWAP (11, the value, with offset, calculated before) and 52: MSTORE
        "80" + // duplicate again for future
            "6040" + "01" + "6016" + //duplicate MSIZE again, add to 40 (as offset) 
                "90" + "52" + //push1 0x16 (22), swap with offset, and MSTORE
        "80" + // duplicate one more time
            "6060" + "01" +"6021"+ //duplicate MSIZE again, add to 60 (as offset)
                "90" + "52" +  //push1 0x21 (33)m Swap with offset and MSTORE
                // at this point the diplicated MSIZE is still one the stack.. for one last use
        "6080" + "90" + "52" + // now push1 0x80, swap with MSIZE (still on stack) and MSTORE(offset=MSIZE, val=0x80) i.e.128 use for MEMSTOP
        "6000" + //is this push1 for msg.value? .. no, probalbly 4 0 bytes for function signature
        "73" + //push20 (for addr)        
        "77045e71a7a2c50903d88e564cd72fab11e82051"+ //contract A addr
            "6203e800" + //push3, 03e800 (256K). orig 61 03e8 (1000gas).. but SSTORE=20000! Was OOG 
            "f1"+ // CALL : order: gas->addr->value->argsOffset->argsLength>retOffset->retLengt
        /* do stuff with call return and stop*/
        "6020" + "6000" + "02" +// multiply 0x20 (32) and 0
        "60a0" + // Push1 10*16 = 160
        "01" + // add
        "6020" + "01" + //ADD 0x20 (32)
        "51" + //MLOAD
        "6000" + "52" + //MSTORE starting pos 0  
        "00"; //STOP
        
        /**
        * contract Contract {
            function main() {
                memory[0x1f:0x20] = 0x00; //memory8 [31-32] store 0 (1 byte)
                memory[MSIZE():MSIZE() + 0x20] = 0xe0; //240 in next 32 byte slot (dataword)
                memory[MSIZE() + 0xc0:MSIZE() + 0xc0 + 0x01] = 0x00;
                var temp0 = MSIZE(); //current size -> t0 //this will be duplicated OPCODE 80 several times
                memory[temp0 + 0x20:temp0 + 0x20 + 0x20] = 0x0b; //11 at [t0+96:t0+128]
                memory[temp0 + 0x40:temp0 + 0x40 + 0x20] = 0x16; //22 at [t0+64:t0+96]
                memory[temp0 + 0x60:temp0 + 0x60 + 0x20] = 0x21; //33 at [t0+32:t0+64]
                memory[temp0:temp0 + 0x20] = 0x80;              //128 at [t0:t0+32] bytes //memStop? for callData
                var temp1;
                temp1, memory[MSIZE():MSIZE() + 0xe0] = address(0x77045e71a7a2c50903d88e564cd72fab11e82051).call.gas(0x03e800)(memory[MSIZE() + 0x20:MSIZE() + 0x20 + 0x60]);
                memory[0x00:0x20] = memory[0xc0:0xe0];
                stop();
                }
            }
         */
        /**this is the structure for handling  CALL return: 
         * success, memory[retOffset:retOffset+retLength] = address(addr).call.gas(gas).value(value)
                (memory[argsOffset:argsOffset+argsLength])
        */    
        //RskAddress caller_addr = new RskAddress(caller_addr_str);

        RskAddress contractA_addr = new RskAddress(contractA_addr_str);
        byte[] codeA = Hex.decode(code_a);

        RskAddress contractB_addr = new RskAddress(contractB_addr_str);
        byte[] codeB = Hex.decode(code_b);

        ProgramInvokeMockImpl pi = new ProgramInvokeMockImpl();
        pi.setOwnerAddress(contractB_addr);
        Repository repository = pi.getRepository();

        repository.createAccount(contractA_addr);
        repository.setupContract(contractA_addr); 
        repository.saveCode(contractA_addr, codeA);
        
        //Before the call, check the values stored in contract storage
        repository.addStorageRow(contractA_addr, DataWord.valueOf(00), DataWord.valueOf(1));
        repository.addStorageRow(contractA_addr, DataWord.valueOf(01), DataWord.valueOf(2));
        
        //Set previous values before call
        DataWord value_1a = repository.getStorageValue(contractA_addr, DataWord.valueOf(00));
        DataWord value_2a = repository.getStorageValue(contractA_addr, DataWord.valueOf(01));
        assertEquals(1L, value_1a.longValue());
        assertEquals(2L, value_2a.longValue());    

        // 2 values that we expect to be stored
        long expectedVal_1 = 11;
        long expectedVal_2 = 22;

        repository.createAccount(contractB_addr);
        repository.setupContract(contractB_addr);
        repository.saveCode(contractB_addr, codeB);

        //repository.createAccount(caller_addr);
        //final BigInteger value = new BigInteger("100000000000000000000");
        //repository.addBalance(caller_addr, new Coin(value));


        // ****************** //
        //  Play the program  //
        // ****************** //
        VM vm = getSubject();
        Program program = getProgram(codeB, pi);

        try {
            while (!program.isStopped())
                vm.step(program);
        } catch (RuntimeException e) {
            program.setRuntimeFailure(e);
        }
        //after the call, check the values stored in contract storage
        DataWord value_1 = repository.getStorageValue(contractA_addr, DataWord.valueOf(00));
        DataWord value_2 = repository.getStorageValue(contractA_addr, DataWord.valueOf(01));


        assertEquals(expectedVal_1, value_1.longValue());
        assertEquals(expectedVal_2, value_2.longValue());

        // TODO: check that the value pushed after exec is 1

        ProgramResult result = program.getResult();
                
        String logString = "\n============ Results ============" +"\n" +
                    "Gas used: " + result.getGasUsed() + "\n" +
                    "Rent gas used: " + result.getRentGasUsed() + "\n" +
                    "No. trie nodes with `updated` rent timestamp: " +  result.getAccessedNodes().size() +
                    "\nNew trie nodes created (6 months rent): " +  result.getCreatedNodes().size() + 
                    "\nTotal trie nodes touched by tx: " + result.getKeysSeenBefore().size() + "\n";


        logger.info(logString);


    }

    //#created december 2020
    /** test5Call and test5DelegateCall ensure that rentTracking is not duplicated for
     * nodes seen at different call stacks depths. 
     * A. Pass a set of all keys seen thus far to Calls (using `getProgramV2()`)
     * B. Delegate call is another way of doing this. Interact with the same storage cells from different call depths
     * 
     * Cascade: B -> Call (C) -> Call (A) and B -> Call(C) -> DelegateCall (A)
     * The number of nodes we expect to track in these tests
     * - 5 nodes each for contracts A and C: Account, Code, StorageRoot, 2 Storage Cells
     * - 3 for contract B: Account, code, and storage root (no interactions with cells)
     * Total of 13 nodes
     * Tracker will skip collecting rent (too low) for 4 of these (Code for A), and storage root for all 3 contracts will
     * test5DelegateCall will not interact with contract A's storage cells anymore.. thus reducing total nodes tracked to 11
     * 
    */
    
    @Test 
    public void test5Call() { //based on test2
        /**This test: B -> Call(C), C -> Call(A) .. test2 has B -> Call(A) directly
         * Contract A: As in test2, it stores the first 2 values of calldata in msg received in storage
         * Contract B: In test2, it stores 3 values (hardcoded to 11, 22, 33) in memory. 
         *  - Then makes a call to contract A with those values. The first 2 get stored.
         *  - the only change we make here (from test2), is the address of A is replaced with that of C's.
         * Contract C: Different from test 2. 
         *  - This combines contract A and contract B.. 
         *  - It stores the first 2 values (11 and 22) received from contract B in its own storage. 
         *  - and it calls contract A with 3 values of its own (44,55,66), i.e. (0x2c,0x37, 0x42)
         * -  the first 2 values (44,55) will get stored
         * To check that rent tracking/collection does not happen more than once in a tx, we run the
         * program a second time.. but this time, we pass a set of trie keys that were seen in the first run.
         * 
         */
        // Set contract into Database
        String contractA_addr_str = "77045e71a7a2c50903d88e564cd72fab11e82051";
        String contractB_addr_str = "83c5541a6c8d2dbad642f385d8d06ca9b6c731ee";
        String contractC_addr_str = "cd2a3d9f938e13cd947ec05abc7fe734df8dd826";

        // original
        //String code_a = "60006020023560005260016020023560205260005160005560205160015500";
        
        String code_a = "6000" + "6020"+ //push1 0, push1 20
                            "02"+ // MUL (multiply 0 with 32) .. could hv skipped 6020 02, MUL used for composition
                        "35" + //CALLDATALOAD
                        "6000" + "52" +// push1 0, MSTORE
                        "6001" + "6020"+ //push1 1, push1 20 .. could have been 4*8 i.e. 6004 6008 02, 
                            "02" + // MUL (multiply 1 with 32) .. or just 6020 instead of without 6001 6020 02
                        "35" +  //CALLDATALOAD
                        "6020" + "52" + //push1 20, another MSTORE
                            "6000" + "51" + //push1 0, MLOAD (32 bytes) starting pos 0, 
                        "6000" + "55" + //push1 0 (KEY), SSTORE 
                            "6020" + "51" + //push1 20, MLOAD (32 bytes)starting pos 0x20 (i.e 32)
                        "6001" + "55" + // push1 1 (KEY), SSTORE
                            "00"; //stop
        
        /** decompiled 
         * contract Contract {
            function main() {
                memory[0x00:0x20] = msg.data[0x00:0x20]; //32 bytes data
                memory[0x20:0x40] = msg.data[0x20:0x40]; // -do- 
                storage[0x00] = memory[0x00:0x20]; //store in cell 1
                storage[0x01] = memory[0x20:0x40]; //store in cell 2
                stop();
                }
            }
         */
        
         //the original from test2
        //String code_b =  "6000601f5360e05960e05952600060c05901536060596020015980602001600b9052806040016016905280606001602190526080905260007377045e71a7a2c50903d88e564cd72fab11e820516203e800f1602060000260a0016020015160005200";
        
        //Can be commented out. This is not used in this test.. but variations are
        String code_b = "6000" +"601f" + "53" + //push1 0 (value), push1 31 (offset) to MSTORE8 
        "60e0"+ "59" + //push1 224 (value)  MSIZE (offset)
        "60e0" +"59" + //and again
        "52" + //MSTORE
        "6000" + "60c0" + "59" + "01" +"53" + //MSTORE8 (offset: MSIZE + 0xc0, value:00 ) //
        "6060" + "59" + "6020" + "01" + //
        "59" + //MSIZE current memory size, this will be reference for setting offsets
        "80" + //duplicate it, so it is available, after each use of it as a reference
            "6020" + "01" + "600b" +//add 20 to duplicated MSIZE, then push1 0x0b (11) to stack
                "90" + "52" + // then 90: SWAP (11, the value, with offset, calculated before) and 52: MSTORE
        "80" + // duplicate again for future
            "6040" + "01" + "6016" + //duplicate MSIZE again, add to 40 (as offset) 
                "90" + "52" + //push1 0x16 (22), swap with offset, and MSTORE
        "80" + // duplicate one more time
            "6060" + "01" +"6021"+ //duplicate MSIZE again, add to 60 (as offset)
                "90" + "52" +  //push1 0x21 (33)m Swap with offset and MSTORE
                // at this point the duplicated MSIZE is still one the stack.. for one last use
        "6080" + "90" + "52" + // now push1 0x80, swap with MSIZE (still on stack) and MSTORE(offset=MSIZE, val=0x80) i.e.128 use for MEMSTOP
        "6000" + //is this push1 for msg.value? .. no, probalbly 4 0 bytes for function signature
        "73" + //push20 (for addr)        
        "77045e71a7a2c50903d88e564cd72fab11e82051"+ //contract A addr
            "5a"+ //change specific Gas to all available gas
            //"6203e800" + //push3, 03e800 (256K). orig 61 03e8 (1000gas).. but SSTORE=20000! Was OOG 
            "f1"+ // CALL
        // do stuff with call return
        "6020" + "6000" + "02" +// multiply 0x20 and 0
        "60a0" + // Push1 10*16 = 160
        "01" + // add
        "6020" + "01" + //ADD 0x20
        "51" + //MLOAD
        "6000" + "52" + //MSTORE starting pos 0 
        "00"; //STOP
        
        /**
        * contract Contract {
            function main() {
                memory[0x1f:0x20] = 0x00; //memory8 [31-32] store 0 (1 byte)
                memory[MSIZE():MSIZE() + 0x20] = 0xe0; //240 in next 32 byte slot (dataword)
                memory[MSIZE() + 0xc0:MSIZE() + 0xc0 + 0x01] = 0x00;
                var temp0 = MSIZE(); //current size -> t0 //this will be duplicated OPCODE 80 several times
                memory[temp0 + 0x20:temp0 + 0x20 + 0x20] = 0x0b; //11 at [t0+96:t0+128]
                memory[temp0 + 0x40:temp0 + 0x40 + 0x20] = 0x16; //22 at [t0+64:t0+96]
                memory[temp0 + 0x60:temp0 + 0x60 + 0x20] = 0x21; //33 at [t0+32:t0+64]
                memory[temp0:temp0 + 0x20] = 0x80;              //128 at [t0:t0+32] bytes //memStop? for callData
                var temp1;
                temp1, memory[MSIZE():MSIZE() + 0xe0] = address(0x77045e71a7a2c50903d88e564cd72fab11e82051).call.gas(0x03e800)(memory[MSIZE() + 0x20:MSIZE() + 0x20 + 0x60]);
                memory[0x00:0x20] = memory[0xc0:0xe0];
                stop();
                }
            }
         */
        
         //new code for B.. it should call C
        //The only change is the address it calls (replace A's with C's)
        String code_B2C = "6000601f5360e05960e05952600060c05901536060596020015980602001600b905280604001601690528060600160219052608090526000"+
        "73"+"cd2a3d9f938e13cd947ec05abc7fe734df8dd826"+ // replace addr A with C 
        "5a" + //"6203e800
        "f1" +
        "602060000260a0016020015160005200";
        
        // C will call A.
        // C has the same code as A (stores 2 values from calldata). But it also has all the code from B (including A's address)
        // So, it does both.. this is more like a chain, or a cascade
        String code_C2A = "6000602002356000526001602002356020526000516000556020516001556000601f5360e05960e05952600060c059015360605960200159"+
            "80602001"+ "602c" + //"600b" + replace 11 with 44
        "905280604001"+ "6037" + //"6016" + replace 22 with 55
        "905280606001"+ "6042" + //"6021" + replace 33 with 66
        "905260809052"+
        "6000"+ //value needed with call, even if 0. Not needed with delegate call (f4).
        "73"+ "77045e71a7a2c50903d88e564cd72fab11e82051"+ // contract A's address
        "5a" + //"6203e800"+ //GAS was specific value
        "f1" + //CALL OR, to keep storage of caller use delegatecall
        "602060000260a0016020015160005200";

        RskAddress contractA_addr = new RskAddress(contractA_addr_str);
        byte[] codeA = Hex.decode(code_a);

        RskAddress contractB_addr = new RskAddress(contractB_addr_str);
        //byte[] codeB = Hex.decode(code_b);
        byte[] codeB = Hex.decode(code_B2C);

        RskAddress contractC_addr = new RskAddress(contractC_addr_str);
        //byte[] codeC = Hex.decode(code_c);
        byte[] codeC = Hex.decode(code_C2A);

        ProgramInvokeMockImpl pi = new ProgramInvokeMockImpl();
        pi.setOwnerAddress(contractB_addr);
        pi.setGas(100_000L); 
        pi.setRentGas(100_000L);
        Repository repository = pi.getRepository();

        repository.createAccount(contractA_addr);
        repository.setupContract(contractA_addr); 
        repository.saveCode(contractA_addr, codeA);
        
        repository.createAccount(contractB_addr);
        repository.setupContract(contractB_addr);
        repository.saveCode(contractB_addr, codeB);

        repository.createAccount(contractC_addr);
        repository.setupContract(contractC_addr);
        repository.saveCode(contractC_addr, codeC);

        //Before the call, check the values stored in contract storage
        repository.addStorageRow(contractA_addr, DataWord.valueOf(00), DataWord.valueOf(10L));
        repository.addStorageRow(contractA_addr, DataWord.valueOf(01), DataWord.valueOf(20L));
        repository.addStorageRow(contractC_addr, DataWord.valueOf(00), DataWord.valueOf(30L));
        repository.addStorageRow(contractC_addr, DataWord.valueOf(01), DataWord.valueOf(40L));
        
        //Set previous values before call
        DataWord value_1a = repository.getStorageValue(contractA_addr, DataWord.valueOf(00));
        DataWord value_2a = repository.getStorageValue(contractA_addr, DataWord.valueOf(01));
        assertEquals(10L, value_1a.longValue());
        assertEquals(20L, value_2a.longValue());
        //for C
        DataWord value_1c = repository.getStorageValue(contractC_addr, DataWord.valueOf(00));
        DataWord value_2c = repository.getStorageValue(contractC_addr, DataWord.valueOf(01));
        assertEquals(30L, value_1c.longValue());
        assertEquals(40L, value_2c.longValue());    

        //values we expect to be stored
        long expectedVal_1c = 11;
        long expectedVal_2c = 22;
        
        long expectedVal_1a = 44;
        long expectedVal_2a = 55;
        

        // ****************** //
        //  Play the program  //
        // ****************** //
        VM vm = getSubject();
        Program program = getProgram(codeB, pi);

        try {
            while (!program.isStopped())
                vm.step(program);
        } catch (RuntimeException e) {
            program.setRuntimeFailure(e);
        }
        //after the call, check the values stored in contract storage
        value_1a = repository.getStorageValue(contractA_addr, DataWord.valueOf(00));
        value_2a = repository.getStorageValue(contractA_addr, DataWord.valueOf(01));
        value_1c = repository.getStorageValue(contractC_addr, DataWord.valueOf(00));
        value_2c = repository.getStorageValue(contractC_addr, DataWord.valueOf(01));


        assertEquals(expectedVal_1a, value_1a.longValue());
        assertEquals(expectedVal_2a, value_2a.longValue());
        assertEquals(expectedVal_1c, value_1c.longValue());
        assertEquals(expectedVal_2c, value_2c.longValue());

        // TODO: check that the value pushed after exec is 1

        ProgramResult result = program.getResult();
                
        String logString = "\n\n============ Results ============\n" +
                    "\nGas used: " + result.getGasUsed() +             
                    "\nGas remaining: " + (pi.getGas()-result.getGasUsed()) + "\n" + 
                    "\nRent gas used: " + result.getRentGasUsed() + 
                    "\nRentGas remaining: " + (pi.getRentGas()- result.getRentGasUsed()) + "\n" +             
                    "\nNo. trie nodes with `updated` rent timestamp: " +  result.getAccessedNodes().size() +
                    "\nNew trie nodes created (6 months rent): " +  result.getCreatedNodes().size() + 
                    "\nTotal trie nodes touched by tx: " + result.getKeysSeenBefore().size()+"\n";
                    //"\nKeys:\n" + result.getKeysSeenBefore();


        logger.info(logString);
        //once keys are added at some depth,rent computations will not be triggerred again (will exit)
        Program program2 = getProgramV2(codeB, pi, result.getKeysSeenBefore());

        try {
            while (!program2.isStopped())
                vm.step(program2);
        } catch (RuntimeException e) {
            program2.setRuntimeFailure(e);
        }
        ProgramResult result2 = program2.getResult();
                
        logString = "\n\n============ Results 2=============\n" +
                    "\nGas used: " + result2.getGasUsed() + 
                    "\nGas remaining: " + (pi.getGas()-result.getGasUsed()- result2.getGasUsed()) + "\n" +
                    "\nRent gas used: " + result2.getRentGasUsed() + 
                    "\nRentGas remaining: " + (pi.getRentGas()- result.getRentGasUsed() - result2.getRentGasUsed()) +"\n" +
                    "\nNo. trie nodes with `updated` rent timestamp: " +  result2.getAccessedNodes().size() +
                    "\nNew trie nodes created (6 months rent): " +  result2.getCreatedNodes().size() + 
                    "\nTotal trie nodes touched by tx: " + result2.getKeysSeenBefore().size()+"\n";
        logger.info(logString);

        assertEquals(result.getGasUsed(),result2.getGasUsed());
        assertEquals(0L,result2.getRentGasUsed());
        assertEquals(result.getKeysSeenBefore().size(),result2.getKeysSeenBefore().size());
        //2 Less with delegate call as contract A's storage cells are not touched by the transaction
        assertEquals(13L,result2.getKeysSeenBefore().size());

        // REPEAT: but this time pass an empty set.. should be the same as the very first run
        // rent will get tracked again
        program2 = getProgramV2(codeB, pi, new HashSet<>());

        try {
            while (!program2.isStopped())
                vm.step(program2);
        } catch (RuntimeException e) {
            program2.setRuntimeFailure(e);
        }
        result2 = program2.getResult();

        logString = "\n\n============ Results 2 (mod)=============\n" +
                    "\nGas used: " + result2.getGasUsed() + 
                    "\nGas remaining: " + (pi.getGas()-result.getGasUsed()- result2.getGasUsed()) + "\n" +
                    "\nRent gas used: " + result2.getRentGasUsed() + 
                    "\nRentGas remaining: " + (pi.getRentGas()- result.getRentGasUsed() - result2.getRentGasUsed()) +"\n" +
                    "\nNo. trie nodes with `updated` rent timestamp: " +  result2.getAccessedNodes().size() +
                    "\nNew trie nodes created (6 months rent): " +  result2.getCreatedNodes().size() + 
                    "\nTotal trie nodes touched by tx: " + result2.getKeysSeenBefore().size()+"\n";
        logger.info(logString);


        assertEquals(result.getGasUsed(),result2.getGasUsed());
        assertEquals(result.getRentGasUsed(),result2.getRentGasUsed());
        assertEquals(result.getKeysSeenBefore().size(),result2.getKeysSeenBefore().size());
        //2 Less with delegate call as contract A's storage cells are not touched by the transaction
        assertEquals(13L,result2.getKeysSeenBefore().size());    
    }


    @Test
    public void test5DelegateCall() {
        /**same as test5Call() but uses delegate call
         * This test: B -> CALL(C), C-> DelegateCall(A) .. test2 has B -> Call(A) directly
         * Contract A: As in test2, it stores the first 2 values of calldata in msg received in storage
         * Contract B: In test2, it stores 3 values (hardcoded to 11, 22, 33) in memory. 
         *  - Then makes a call to contract A with those values. The first 2 get stored.
         *  - the only change we make here (from test2), is the address of A is replaced with that of C's.
         * Contract C: Different from test 2. 
         *  - This combines contract A and contract B.. 
         *  - It stores the first 2 values (11 and 22) received from contract B in its own storage. 
         *  - and it DELEGATE calls contract A with 3 values of its own (44,55,66), i.e. (0x2c,0x37, 0x42)
         * -  the first 2 values (44,55) will get stored in contract C's OWN storage (DELEGATE CALL)
         * To check that rent tracking/collection does not happen more than once in a tx, we run the
         * program a second time.. but this time, we pass a set of trie keys that were seen in the first run.
         */
        // Set contract into Database
        String contractA_addr_str = "77045e71a7a2c50903d88e564cd72fab11e82051";
        String contractB_addr_str = "83c5541a6c8d2dbad642f385d8d06ca9b6c731ee";
        String contractC_addr_str = "cd2a3d9f938e13cd947ec05abc7fe734df8dd826";

        // Contract A will be called by C, which in turn will be called by B
        String code_a = "60006020023560005260016020023560205260005160005560205160015500";
         
        //For contract B which will call Contract C
        //The only change from test2() is the address it calls (replace A's with C's)
        String code_B2C = "6000601f5360e05960e05952600060c05901536060596020015980602001600b90528060400160169052806060016021905260809052"+
        "6000"+
        "73"+"cd2a3d9f938e13cd947ec05abc7fe734df8dd826"+ // replace addr A with C 
        "5a" + //"6203e800
        "f1" + //f1 or f4
        "602060000260a0016020015160005200";
        
        // contract C will call contract A.
        // C has the same code as A (stores 2 values from calldata). 
        // C also has all the code from B (including A's address)
        // So, it does both.. this is more like a chain, or a cascade
        String code_C2A = "6000602002356000526001602002356020526000516000556020516001556000601f5360e05960e05952600060c059015360605960200159"+
            "80602001"+ "602c" + //"600b" + replace 11 with 44
        "905280604001"+ "6037" + //"6016" + replace 22 with 55
        "905280606001"+ "6042" + //"6021" + replace 33 with 66
        "905260809052"+
        //"6000"+ //value needed with call, even if 0. Not needed with delegate call (f4).
        "73"+ "77045e71a7a2c50903d88e564cd72fab11e82051"+ // contract A's address
        "5a" + //"6203e800"+ //GAS was specific value
        //"f1" + //CALL OR, to keep storage of caller use delegatecall
        "f4"+ //DELEGATECALL .. retain context of caller
        "602060000260a0016020015160005200";

        RskAddress contractA_addr = new RskAddress(contractA_addr_str);
        byte[] codeA = Hex.decode(code_a);

        RskAddress contractB_addr = new RskAddress(contractB_addr_str);
        //byte[] codeB = Hex.decode(code_b);
        byte[] codeB = Hex.decode(code_B2C);

        RskAddress contractC_addr = new RskAddress(contractC_addr_str);
        //byte[] codeC = Hex.decode(code_c);
        byte[] codeC = Hex.decode(code_C2A);

        ProgramInvokeMockImpl pi = new ProgramInvokeMockImpl();
        pi.setOwnerAddress(contractB_addr);
        pi.setGas(100_000L); 
        pi.setRentGas(100_000L);
        Repository repository = pi.getRepository();

        repository.createAccount(contractA_addr);
        repository.setupContract(contractA_addr); 
        repository.saveCode(contractA_addr, codeA);
        
        repository.createAccount(contractB_addr);
        repository.setupContract(contractB_addr);
        repository.saveCode(contractB_addr, codeB);

        repository.createAccount(contractC_addr);
        repository.setupContract(contractC_addr);
        repository.saveCode(contractC_addr, codeC);

        //Before the call, check the values stored in contract storage
        repository.addStorageRow(contractA_addr, DataWord.valueOf(00), DataWord.valueOf(10L));
        repository.addStorageRow(contractA_addr, DataWord.valueOf(01), DataWord.valueOf(20L));
        repository.addStorageRow(contractC_addr, DataWord.valueOf(00), DataWord.valueOf(30L));
        repository.addStorageRow(contractC_addr, DataWord.valueOf(01), DataWord.valueOf(40L));
        
        //Set previous values before call
        DataWord value_1a = repository.getStorageValue(contractA_addr, DataWord.valueOf(00));
        DataWord value_2a = repository.getStorageValue(contractA_addr, DataWord.valueOf(01));
        assertEquals(10L, value_1a.longValue());
        assertEquals(20L, value_2a.longValue());
        //for C
        DataWord value_1c = repository.getStorageValue(contractC_addr, DataWord.valueOf(00));
        DataWord value_2c = repository.getStorageValue(contractC_addr, DataWord.valueOf(01));
        assertEquals(30L, value_1c.longValue());
        assertEquals(40L, value_2c.longValue());    

        //values we expect to be stored
        long expectedVal_1c = 44; // 11 stored initially will be overwritten DELEGATECALL;
        long expectedVal_2c = 55; //22 stored initially will be overwritten DELEGATECALL;
        
        // because of delegate call, these nodes will NOT be touched by the program
        // they will NOT be added to rent tracker
        long expectedVal_1a = 10; //no change since C will call A with DelegateCall
        long expectedVal_2a = 20; //  -same-
        

        // ****************** //
        //  Play the program  //
        // ****************** //
        VM vm = getSubject();
        Program program = getProgram(codeB, pi);

        try {
            while (!program.isStopped())
                vm.step(program);
        } catch (RuntimeException e) {
            program.setRuntimeFailure(e);
        }
        //after the call, check the values stored in contract storage
        value_1a = repository.getStorageValue(contractA_addr, DataWord.valueOf(00));
        value_2a = repository.getStorageValue(contractA_addr, DataWord.valueOf(01));
        value_1c = repository.getStorageValue(contractC_addr, DataWord.valueOf(00));
        value_2c = repository.getStorageValue(contractC_addr, DataWord.valueOf(01));


        assertEquals(expectedVal_1a, value_1a.longValue());
        assertEquals(expectedVal_2a, value_2a.longValue());
        assertEquals(expectedVal_1c, value_1c.longValue());
        assertEquals(expectedVal_2c, value_2c.longValue());

        // TODO: check that the value pushed after exec is 1

        ProgramResult result = program.getResult();
                
        String logString = "\n\n============ Results ============\n" +
                    "\nGas used: " + result.getGasUsed() +             
                    "\nGas remaining: " + (pi.getGas()-result.getGasUsed()) + "\n" + 
                    "\nRent gas used: " + result.getRentGasUsed() + 
                    "\nRentGas remaining: " + (pi.getRentGas()- result.getRentGasUsed()) + "\n" +             
                    "\nNo. trie nodes with `updated` rent timestamp: " +  result.getAccessedNodes().size() +
                    "\nNew trie nodes created (6 months rent): " +  result.getCreatedNodes().size() + 
                    "\nTotal trie nodes touched by tx: " + result.getKeysSeenBefore().size()+"\n";

        logger.info(logString);
        //once keys are added at some depth,rent computations will not be triggerred again (will exit)
        Program program2 = getProgramV2(codeB, pi, result.getKeysSeenBefore());

        try {
            while (!program2.isStopped())
                vm.step(program2);
        } catch (RuntimeException e) {
            program2.setRuntimeFailure(e);
        }
        ProgramResult result2 = program2.getResult();
                
        logString = "\n\n============ Results 2=============\n" +
                    "\nGas used: " + result2.getGasUsed() + 
                    "\nGas remaining: " + (pi.getGas()-result.getGasUsed()- result2.getGasUsed()) + "\n" +
                    "\nRent gas used: " + result2.getRentGasUsed() + 
                    "\nRentGas remaining: " + (pi.getRentGas()- result.getRentGasUsed() - result2.getRentGasUsed()) +"\n" +
                    "\nNo. trie nodes with `updated` rent timestamp: " +  result2.getAccessedNodes().size() +
                    "\nNew trie nodes created (6 months rent): " +  result2.getCreatedNodes().size() + 
                    "\nTotal trie nodes touched by tx: " + result2.getKeysSeenBefore().size()+"\n";
        logger.info(logString);
        assertEquals(result.getGasUsed(),result2.getGasUsed());
        assertEquals(0L,result2.getRentGasUsed());
        assertEquals(result.getKeysSeenBefore().size(),result2.getKeysSeenBefore().size());
        //2 Less with delegate call as contract A's storage cells are not touched by the transaction
        assertEquals(11L,result2.getKeysSeenBefore().size());

    }

    @Ignore //#mish:  the ignore is from before. VM error.. stack size different from expecte
    @Test // contractB call contractA with return expectation
    public void test3() {

        /**
         *       #The code will run
         *       ------------------

         contract A: 77045e71a7a2c50903d88e564cd72fab11e82051
         ---------------

           a = 11
           b = 22
           c = 33
           d = 44
           e = 55
           f = 66

           [asm  192 0 RETURN asm]



         contract B: 83c5541a6c8d2dbad642f385d8d06ca9b6c731ee
         -----------
             a = msg((tx.gas / 10 * 8), 0x77045e71a7a2c50903d88e564cd72fab11e82051, 0, [11, 22, 33], 3, 6)

         */

        long expectedVal_1 = 11;
        long expectedVal_2 = 22;
        long expectedVal_3 = 33;
        long expectedVal_4 = 44;
        long expectedVal_5 = 55;
        long expectedVal_6 = 66;

        // Set contract into Database
        RskAddress caller_addr = new RskAddress("cd2a3d9f938e13cd947ec05abc7fe734df8dd826");

        RskAddress contractA_addr = new RskAddress("77045e71a7a2c50903d88e564cd72fab11e82051");
        RskAddress contractB_addr = new RskAddress("83c5541a6c8d2dbad642f385d8d06ca9b6c731ee");

        byte[] codeA = Hex.decode("600b60005260166020526021604052602c6060526037608052604260a05260c06000f2");
        byte[] codeB = Hex.decode("6000601f5360e05960e05952600060c05901536060596020015980602001600b9052806040016016905280606001602190526080905260007377045e71a7a2c50903d88e564cd72fab11e820516203e800f1602060000260a00160200151600052");

        ProgramInvokeMockImpl pi = new ProgramInvokeMockImpl();
        pi.setOwnerAddress(contractB_addr);
        Repository repository = pi.getRepository();
        repository.createAccount(contractA_addr);
        repository.setupContract(contractA_addr);
        repository.saveCode(contractA_addr, codeA);

        repository.createAccount(contractB_addr);
        repository.setupContract(contractB_addr);
        repository.saveCode(contractB_addr, codeB);

        repository.createAccount(caller_addr);
        final BigInteger value = new BigInteger("100000000000000000000");
        repository.addBalance(caller_addr, new Coin(value));

        // ****************** //
        //  Play the program  //
        // ****************** //
        VM vm = getSubject();
        Program program = getProgram(codeB, pi);

        try {
            while (!program.isStopped())
                vm.step(program);
        } catch (RuntimeException e) {
            program.setRuntimeFailure(e);
        }

        ProgramResult result = program.getResult();
                
        String logString = "\n============ Results ============" +"\n" +
                    "Gas used: " + result.getGasUsed() + "\n" +
                    "Rent gas used: " + result.getRentGasUsed() + "\n" +
                    "No. trie nodes with `updated` rent timestamp: " +  result.getAccessedNodes().size() +
                    "\nNew trie nodes created (6 months rent): " +  result.getCreatedNodes().size() + 
                    "\nTotal trie nodes touched by tx: " + result.getKeysSeenBefore().size() + "\n";


        logger.info(logString);

        DataWord value1 = program.memoryLoad(DataWord.valueOf(32));
        DataWord value2 = program.memoryLoad(DataWord.valueOf(64));
        DataWord value3 = program.memoryLoad(DataWord.valueOf(96));
        DataWord value4 = program.memoryLoad(DataWord.valueOf(128));
        DataWord value5 = program.memoryLoad(DataWord.valueOf(160));
        DataWord value6 = program.memoryLoad(DataWord.valueOf(192));

        //#mish these assertions fail
        assertEquals(expectedVal_1, value1.longValue());
        assertEquals(expectedVal_2, value2.longValue());
        assertEquals(expectedVal_3, value3.longValue());
        assertEquals(expectedVal_4, value4.longValue());
        assertEquals(expectedVal_5, value5.longValue());
        assertEquals(expectedVal_6, value6.longValue());
        

        // TODO: check that the value pushed after exec is 1
    }
    
    //@Ignore
    @Test // CREATE magic
    public void test4() {

        /**
         *       #The code will run
         *       ------------------

         contract A: 77045e71a7a2c50903d88e564cd72fab11e82051
         -----------

             a = 0x7f60c860005461012c6020540000000000000000000000000000000000000000
             b = 0x0060005460206000f20000000000000000000000000000000000000000000000
             create(100, 0 41)              //value = 100, memstart = 0, memsize = 41


         contract B: (the contract to be created the addr will be defined to: 8e45367623a2865132d9bf875d5cfa31b9a0cd94)
         -----------
             a = 200
             b = 300

         */

        // Set contract into Database
        RskAddress caller_addr = new RskAddress("cd2a3d9f938e13cd947ec05abc7fe734df8dd826");

        RskAddress contractA_addr = new RskAddress("77045e71a7a2c50903d88e564cd72fab11e82051");

        byte[] codeA = Hex.decode("7f7f60c860005461012c602054000000000000" + // c8 is 200, 012c is 300  read from A:a
                "00000000000000000000000000006000547e60" + // the 
                "005460206000f2000000000000000000000000" +
                "0000000000000000000000602054602960006064f0"); // 29 = 41, 00 = 0, 64 = 100 (value)  create(100, 0,41)

        ProgramInvokeMockImpl pi = new ProgramInvokeMockImpl();
        //pi.setOwnerAddress(caller_addr); // #mish
        pi.setOwnerAddress(contractA_addr);

        Repository repository = pi.getRepository();
        //System.out.println(repository.getAccountsKeys()); // 2 default addresses

        repository.createAccount(contractA_addr);
        repository.setupContract(contractA_addr);
        repository.saveCode(contractA_addr, codeA);

        repository.createAccount(caller_addr);

        //#mish copied from createcontract in Program to see if the generated address matches what is in the example above
        RskAddress senderAddress = new RskAddress(pi.getOwnerAddress());
        final BigInteger value = new BigInteger("1000000");
        repository.addBalance(senderAddress, new Coin(value));
        byte[] nonce = repository.getNonce(senderAddress).toByteArray();
        byte[] newAddressBytes = HashUtil.calcNewAddr(pi.getOwnerAddress().getLast20Bytes(), nonce);
        RskAddress newAddress = new RskAddress(newAddressBytes);
        System.out.println("\nCreated and expected\n" + newAddress +  "\n8e45367623a2865132d9bf875d5cfa31b9a0cd94");
        System.out.println(repository.getBalance(newAddress));
        // ****************** //
        //  Play the program  //
        // ****************** //
        VM vm = getSubject();
        Program program = getProgram(codeA, pi);

        try {
            while (!program.isStopped())
                vm.step(program);
        } catch (RuntimeException e) {
            System.out.println("error");
            program.setRuntimeFailure(e);
        }

        ProgramResult result = program.getResult();
                
        String logString = "\n============ Results ============" +"\n" +
                    "Gas used: " + result.getGasUsed() + "\n" +
                    "Rent gas used: " + result.getRentGasUsed() + "\n" +
                    "No. trie nodes with `updated` rent timestamp: " +  result.getAccessedNodes().size() +
                    "\nNew trie nodes created (6 months rent): " +  result.getCreatedNodes().size() + 
                    "\nTotal trie nodes touched by tx: " + result.getKeysSeenBefore().size();


        logger.info(logString);

        /*
        System.out.println("*** Call create Size: " + program.getResult().getCallCreateList().size());
        System.out.println(repository.getBalance(newAddress)); // value is transferred
        System.out.println(repository.isContract(newAddress)); // returns true
        System.out.println(repository.getCode(newAddress)); //there is no code to save
        System.out.println(repository.getStorageKeysCount(newAddress)); //nothing stored.. strange
        // how many accounts? should be 3, but there are 4, one from invokemock default contract address
        System.out.println(repository.getAccountsKeys());
        */
    }


    private VM getSubject() {
        return new VM(vmConfig, precompiledContracts);
    }

    //#mish: original program construction.. no set of seen keys passed
    private Program getProgram(byte[] code, ProgramInvoke pi) {
        return new Program(vmConfig, precompiledContracts, blockFactory, mock(ActivationConfig.ForBlock.class), code, pi, null, new HashSet<>());
    }

    //#mish new version with all previously seen keys passed to with the invoke.
    // This should prevent rent computation for previously seen keys
    private Program getProgramV2(byte[] code, ProgramInvoke pi, Set keysSeen) {
        return new Program(vmConfig, precompiledContracts, blockFactory, mock(ActivationConfig.ForBlock.class), code, pi, null, new HashSet<>(), keysSeen);
    }
}
