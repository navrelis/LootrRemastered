package noobanidus.mods.lootr.common.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class TrophyBlock extends Block {
  // The outer bounds of trophy.json: plinth and handles in x and z, the tip of the gem in y. The model is
  // symmetric around the block's vertical axis, so every facing has the same shape.
  private static final VoxelShape SHAPE = Block.box(4, 0, 4, 12, 14, 12);

  public TrophyBlock(Properties properties) {
    super(properties);
  }

  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    return this.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, context.getHorizontalDirection().getOpposite());
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    super.createBlockStateDefinition(builder);
    builder.add(HorizontalDirectionalBlock.FACING);
  }

  @Override
  @SuppressWarnings("deprecation")
  public VoxelShape getShape(BlockState state, BlockGetter worldIn, BlockPos pos, CollisionContext context) {
    return SHAPE;
  }

  @Override
  public BlockState rotate(BlockState p_60530_, Rotation p_60531_) {
    return p_60530_.setValue(HorizontalDirectionalBlock.FACING, p_60531_.rotate(p_60530_.getValue(HorizontalDirectionalBlock.FACING)));
  }

  @Override
  public BlockState mirror(BlockState p_60528_, Mirror p_60529_) {
    return p_60528_.rotate(p_60529_.getRotation(p_60528_.getValue(HorizontalDirectionalBlock.FACING)));
  }
}
